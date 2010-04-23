/*
 * Created on Oct 27, 2004
 */
package no.ntnu.fp.net.co;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.net.ConnectException;
import java.net.InetAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.Stack;

import com.sun.xml.internal.ws.api.message.Packet;

import sun.font.CreatedFontTracker;
import sun.reflect.generics.reflectiveObjects.NotImplementedException;

import no.ntnu.fp.net.admin.Log;
import no.ntnu.fp.net.cl.ClException;
import no.ntnu.fp.net.cl.ClSocket;
import no.ntnu.fp.net.cl.KtnDatagram;
import no.ntnu.fp.net.cl.KtnDatagram.Flag;

/**
 * Implementation of the Connection-interface. <br>
 * <br>
 * This class implements the behaviour in the methods specified in the interface
 * {@link Connection} over the unreliable, connectionless network realised in
 * {@link ClSocket}. The base class, {@link AbstractConnection} implements some
 * of the functionality, leaving message passing and error handling to this
 * implementation.
 * 
 * @author Sebjørn Birkeland and Stein Jakob Nordbø
 * @see no.ntnu.fp.net.co.Connection
 * @see no.ntnu.fp.net.cl.ClSocket
 */
public class ConnectionImpl extends AbstractConnection {

	/** Keeps track of the used ports for each server port. */
	private static Map<Integer, Boolean> usedPorts = Collections
			.synchronizedMap(new HashMap<Integer, Boolean>());

	/**
	 * Initialise initial sequence number and setup state machine.
	 * 
	 * @param myPort -
	 *            the local port to associate with this connection
	 */
	public ConnectionImpl(int myPort) {
		super();
		Log.writeToLog("Setting port: " + myPort, "ConnectionImpl(int)");
		this.myPort = myPort;
		this.myAddress = getIPv4Address();
		Log.writeToLog("Setting IP: " + this.myAddress, "getIPv4Address()");
	}

	private boolean fin_recieved = false;
	private String last_package = null;
	
	private String getIPv4Address() {
		try {
			return InetAddress.getLocalHost().getHostAddress();
		} catch (UnknownHostException e) {
			return "127.0.0.1";
		}
	}

	/**
	 * Establish a connection to a remote location.
	 * 
	 * @param remoteAddress -
	 *            the remote IP-address to connect to
	 * @param remotePort -
	 *            the remote portnumber to connect to If there's an I/O error.
	 * @throws java.net.SocketTimeoutException
	 *             If timeout expires before connection is completed.
	 * @see Connection#connect(InetAddress, int)
	 */
	public void connect(InetAddress remoteAddress, int remotePort)
			throws IOException, SocketTimeoutException {

		this.remoteAddress = remoteAddress.getHostAddress();
		this.remotePort = remotePort;

		// Check if we're connected already
		if (state.ESTABLISHED == this.state) {
			Log.writeToLog("Already connected!", "connect()");
			return;
		}

		// Send SYN to initiate connect
		KtnDatagram packet = this.constructInternalPacket(Flag.SYN);
		// packet.setPayload("dummy");

		try {
			this.simplySendPacket(packet);
			Log.writeToLog(packet, "SYN sendt", "Connect()");
		} catch (Exception e) {
			throw new IOException(e.getMessage());
			// System.out.println("connect() error: " + e.getMessage());
		}

		this.state = State.SYN_SENT;

		// Look for SYNACK
		while ((packet == null) || (packet.getFlag() != Flag.SYN_ACK)) {
			packet = this.receiveAck();
			System.out.println("Looking for SYN_ACK");
		}
		this.remotePort = packet.getSrc_port();
		Log.writeToLog(packet, "SYNACK recieved", "Connect()");

		// Have SYNACK, send ACK
		this.sendAck(packet, false);
		this.state = State.ESTABLISHED;
		Log.writeToLog(packet, "ACK for SYNACK sendt", "Connect()");

		System.out.println("connect() SUCCESS!");
	}

	/**
	 * Listen for, and accept, incoming connections.
	 * 
	 * @return A new ConnectionImpl-object representing the new connection.
	 * @see Connection#accept()
	 */
	public Connection accept() throws IOException, SocketTimeoutException {

		System.out.println("accept()");
		System.out.println(this.lastValidPacketReceived);

		KtnDatagram packet = null;
		this.state = State.LISTEN;
		while ((null == packet) || (packet.getFlag() != Flag.SYN)) {
			packet = this.receivePacket(true);
		}
		this.state = State.SYN_RCVD;

		this.remoteAddress = packet.getSrc_addr();
		this.remotePort = packet.getSrc_port();

		// Calculate new external port
		int myport = (int) (50000 + 1000 * Math.random());
		while (this.usedPorts.containsKey(myport))
			myport = (int) (50000 + 1000 * Math.random());
		this.usedPorts.put(myport, true);

		// Create a new connection to new client
		ConnectionImpl connection = new ConnectionImpl(myport);
		connection.remoteAddress = packet.getSrc_addr();
		connection.remotePort = packet.getSrc_port();
//		connection.usedPorts = this.usedPorts;

		// Send SYN_ACK
		connection.sendAck(packet, true);
		Log.writeToLog("SYN_ACK sent", "accept()");

		// Wait for ACK
		// while ((packet != null) || (packet.getFlag() != Flag.ACK)) {
		// }
		packet = connection.receiveAck(); // Should block until ack recieved

		if (packet == null) {
			// No ACK today - client port blocked?
			connection.state = State.CLOSED;
			String msg = "Connection attempt from " + connection.remoteAddress
					+ ":" + connection.remotePort + " failed";
			System.out.println(msg);
			Log.writeToLog(msg, "accept()");
			return connection;
		}

		Log.writeToLog(packet, "ACK for SYN_ACK recieved", "accept()");
		Log.writeToLog(packet, "setting nextSequenceNo to:"
				+ (packet.getSeq_nr() + 1), "accept()");
		this.nextSequenceNo = (packet.getSeq_nr() + 1);

		connection.state = State.ESTABLISHED;
		System.out.println("accept COOOOONNNECTED!");

		return connection;

	}

	/**
	 * Send a message from the application.
	 * 
	 * @param msg -
	 *            the String to be sent.
	 * @throws ConnectException
	 *             If no connection exists.
	 * @throws IOException
	 *             If no ACK was received.
	 * @see AbstractConnection#sendDataPacketWithRetransmit(KtnDatagram)
	 * @see no.ntnu.fp.net.co.Connection#send(String)
	 */
	public void send(String msg) throws ConnectException, IOException {

		/*
		 * if (State.ESTABLISHED != this.state) {
		 * //this.connect(this.remoteAddress, this.remotePort); throw new
		 * ConnectException("State is not ESTABLISHED"); }
		 */
		System.out.println("sender: " + msg);

		KtnDatagram packet = this.constructDataPacket(msg);
		packet.setDest_addr(this.remoteAddress);
		packet.setDest_port(this.remotePort);

		KtnDatagram packet_rcvd = null;
		while ((packet_rcvd == null) || (packet_rcvd.getFlag() != Flag.ACK)) {
			packet_rcvd = this.sendDataPacketWithRetransmit(packet);
		}

		Log.writeToLog(packet_rcvd, "Send got ACK", "send()");

		// this.nextSequenceNo = (packet.getSeq_nr() + 1);
		Log.writeToLog(packet, "Sent " + msg + " successfully", "send()");
	}

	/**
	 * Wait for incoming data.
	 * 
	 * @return The received data's payload as a String.
	 * @see Connection#receive()
	 * @see AbstractConnection#receivePacket(boolean)
	 * @see AbstractConnection#sendAck(KtnDatagram, boolean)
	 */
	public String receive() throws ConnectException, IOException {

		Log.writeToLog("Running recieve() function", "recieve()");

		KtnDatagram packet = null;

		do {
			try {
				packet = this.receivePacket(false);
			} catch (EOFException e) {
//				this.fin_recieved = true;
				sendAck(disconnectRequest, false);
				state = State.CLOSE_WAIT;
				throw e;
			}
		} while ((null == packet) || (!this.isValid(packet)));

		this.last_package = packet.getPayload().toString();
		Log.writeToLog(packet, "from recieve", "recieve()");

		System.out.println("Recieve() got: " + packet);

		this.sendAck(packet, false);
		 

		return packet.getPayload().toString();
	}

	/**
	 * Close the connection.
	 * @throws  
	 * 
	 * @see Connection#close()
	 */
	public void close() throws IOException {
		System.out.println("closed() has been called");
//		if (this.state != State.ESTABLISHED)
//			throw new IOException("Cannot close() state: " + this.state);

		KtnDatagram packet = null;

		if (this.state == State.CLOSE_WAIT) {
			// ############# Server side
//			Log.writeToLog(packet, "close(), last packet recieved", "close()");
//
//			if (packet.getFlag() != Flag.FIN && disconnectRequest==null)
//				throw new IOException("Close() called without FIN!");
//
//			Log.writeToLog(packet, "Recieved FIN, sending ACK", "close()");
//			this.sendAck(packet, false);
//
//			this.state = State.CLOSE_WAIT;
			packet = this.constructInternalPacket(Flag.FIN);
			try {
				Thread.sleep(50);
				this.simplySendPacket(packet);
				Log.writeToLog(packet, "FIN sendt from server", "Close()");
			} catch (Exception e) {
				throw new IOException(e.getMessage());
				// System.out.println("connect() error: " + e.getMessage());
			}
			this.state = State.LAST_ACK;
			System.out.println("server current state: " + this.state);
			
//			CHEAT!
			//this.state = State.CLOSED;
			//if (true) return;
			
			
			do {
				packet = this.receiveAck();
				//System.out.println("Looking for ACK in state " + this.state);
			}
			 while (packet == null || (packet.getFlag() != Flag.ACK));
			Log.writeToLog(packet, "Server recieved ACK for FIN", "close()");
			this.state = State.CLOSED;
			} 
		if (this.state == state.ESTABLISHED){
			// ################# Client side

			// Send FIN to initiate close()
			packet = this.constructInternalPacket(Flag.FIN);

			try {
				this.simplySendPacket(packet);
				Log.writeToLog(packet, "FIN sendt from client", "Close()");
			} catch (Exception e) {
				throw new IOException(e.getMessage());
				// System.out.println("connect() error: " + e.getMessage());
			}

			this.state = State.FIN_WAIT_1;
			System.out.println("client current state: " + this.state);
			do {
				System.out.println("Looking for ACK for FIN");
				try {
					Thread.currentThread().sleep(50);
				} catch (InterruptedException e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
				}
				packet = this.receiveAck(); // Block until ACK recieved
			} while ((packet == null) || (packet.getFlag() != Flag.ACK));

			Log
					.writeToLog(packet, "Client got ACK for FIN recieved",
							"close()");

			this.state = State.FIN_WAIT_2;
			System.out.println("client current state: " + this.state);
			
			KtnDatagram packet2 = null;

			do {
				packet2 = this.receivePacket(true);
				if ((packet2 == null) || (packet2.getFlag() != Flag.FIN)) {
					packet2 = this.disconnectRequest;
				}
				System.out.println("Looking for FIN in state" + this.state);
			} while ((packet2 == null) || (packet2.getFlag() != Flag.FIN));

			
					
			try {
				Thread.sleep(50);
				this.sendAck(packet2, false); // Block until success
				Log.writeToLog(packet2, "Client recieved FIN, sending ACK","close()");
			} catch (Exception e) {
				throw new IOException(e.getMessage());
				// System.out.println("connect() error: " + e.getMessage());
			}
			

			this.state = State.TIME_WAIT;
			System.out.println("client current state: " + this.state);
			// TODO vent 30s
			this.state = State.CLOSED;
			System.out.println("client current state: " + this.state);

		}
		else {
			throw new IOException("something wrong"); 
		}
	}

	/**
	 * Test a packet for transmission errors. This function should only called
	 * with data or ACK packets in the ESTABLISHED state.
	 * 
	 * @param packet
	 *            Packet to test.
	 * @return true if packet is free of errors, false otherwise.
	 */
	protected boolean isValid(KtnDatagram packet) {

		// Sjekker om checksum er korrekt
		if (packet.getChecksum() != packet.calculateChecksum()) {
			Log.writeToLog(packet, "checksum invalid", "isValid()");
			return false;
		}

		// Sjekker om pakken kommer fra rett IP
		if (!packet.getSrc_addr().equals(this.remoteAddress)) {
			if (this.remoteAddress.equals("127.0.0.1")) {
				if (!packet.getSrc_addr().equals(this.myAddress)) {
					Log.writeToLog(packet, "Invalid source ip: "
							+ packet.getSrc_addr() + " expected: "
							+ this.myAddress, "isValid()");
					return false;
				}
			} else {
				Log.writeToLog(packet, "Invalid source ip: "
						+ packet.getSrc_addr() + " expected: "
						+ this.remoteAddress, "isValid()");
				return false;
			}
		}

		// Sjekker om pakken kommer fra rett port
		if (packet.getSrc_port() != this.remotePort) {
			Log.writeToLog(packet, "Invalid src port: " + packet.getSrc_port()
					+ " expected: " + this.remotePort, "isValid()");
			return false;
		}

		// Sjekker om pakken har rett flagg
		if (!((packet.getFlag() == Flag.NONE) || (packet.getFlag() == Flag.ACK) || (packet
				.getFlag() == Flag.SYN_ACK))) {
			Log.writeToLog(packet, "Invalid flag " + packet.getFlag(),
					"isValid()");
			return false;
		}

		// Sjekker om pakken har rett sekvensnr.
		if (this.lastValidPacketReceived != null) {
			if (packet.getSeq_nr() != this.lastDataPacketSent.getSeq_nr()) {
				Log.writeToLog(packet, "Invalid seq nr: " + packet.getSeq_nr()
						+ " expected: " + this.lastDataPacketSent.getSeq_nr(),
						"isValid()");
				return false;
			}

			// Skjekk for duplikat pakke
			if (packet.getPayload().equals(this.last_package)) {
				Log.writeToLog(packet, "Duplicate package detected: "
						+ packet.getFlag(), "isValid()");
				return false;
			}
		}

		Log.writeToLog(packet, "Packet is valid", "isValid()");

		// All checks passed
		return true;
	}

}
