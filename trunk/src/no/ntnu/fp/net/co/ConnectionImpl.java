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
		packet.setPayload("dummy");

		try {
			this.simplySendPacket(packet);
		} catch (Exception e) {
			throw new IOException(e.getMessage());
			// System.out.println("connect() error: " + e.getMessage());
		}

		this.state = State.SYN_SENT;

		// Look for SYNACK
		packet = this.receiveAck();

		// while ((null == packet) || (packet.getFlag() != Flag.SYN_ACK)) {
		//	
		// }
		// Have SYNACK, send ACK

		int myport = (int) (50000 + 1000 * Math.random());
		while (this.usedPorts.containsKey(myport))
			myport = (int) (50000 + 1000 * Math.random());

		// this.usedPorts.keySet(myport,true);
		this.usedPorts.put(myport, true);

		ConnectionImpl connection = new ConnectionImpl(myport);
		connection.remoteAddress = packet.getSrc_addr();
		connection.remotePort = packet.getSrc_port();
		connection.usedPorts = this.usedPorts;
		connection.sendAck(packet, false);

		this.state = State.ESTABLISHED;
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
		Log.writeToLog(packet, "null", "crap");

		System.out.println("serverASDF: ");
		System.out.println(packet);

		this.state = State.SYN_RCVD;

		this.remoteAddress = packet.getSrc_addr();
		this.remotePort = packet.getSrc_port();

		int myport = (int) (50000 + 1000 * Math.random());
		while (this.usedPorts.containsKey(myport))
			myport = (int) (50000 + 1000 * Math.random());

		// this.usedPorts.keySet(myport,true);
		this.usedPorts.put(myport, true);

		ConnectionImpl connection = new ConnectionImpl(myport);
		connection.remoteAddress = packet.getSrc_addr();
		connection.remotePort = packet.getSrc_port();
		connection.usedPorts = this.usedPorts;
		// Send SYN_ACK
		connection.sendAck(packet, true);

		// Wait for ACK
		packet = this.receiveAck();
		// while (null == packet || (packet.getFlag() != Flag.ACK)) {
		// }

		System.out.println("accept COOOOONNNECTED!");
		this.state = State.ESTABLISHED;

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

		if (State.ESTABLISHED != this.state)
			throw new ConnectException();
		System.out.println("sender: " + msg);

		KtnDatagram packet = this.constructDataPacket(msg);
		packet.setDest_addr(this.remoteAddress);
		packet.setDest_port(this.remotePort);

		this.sendDataPacketWithRetransmit(packet);

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

		KtnDatagram packet = null;
		while (null == packet) {
			packet = this.receivePacket(false);
		}
		// TODO Check if valid before returning
		this.sendAck(packet, false);

		System.out.println("Recieve() got: "+packet.getPayload().toString());
		
		return packet.getPayload().toString();
	}

	/**
	 * Close the connection.
	 * 
	 * @see Connection#close()
	 */
	public void close() throws IOException {
		throw new NotImplementedException();
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
		boolean valid = true;
		// Sjekker om checksum er korrekt
		if (packet.getChecksum() != packet.calculateChecksum())
			valid = false;

		// Sjekker om pakken kommer fra rett IP
		if (packet.getSrc_addr().equals(this.remoteAddress))
			valid = false;

		// Sjekker om pakken kommer fra rett port
		if (packet.getSrc_port() != this.remotePort)
			valid = false;

		// Sjekker om pakken har rett flagg
		if (!(packet.getFlag() == Flag.NONE || packet.getFlag() == Flag.ACK))
			valid = false;

		// Sjekker om pakken har rett sekvensnr.
		if (packet.getSeq_nr() != (lastValidPacketReceived.getSeq_nr() + 1))
			valid = false;

		// Skriver til log
		if (valid)
			Log.writeToLog(packet, "Valid package", "isValid(KtnDatagram)");
		else
			Log.writeToLog(packet, "Invalid package", "isValid(KtnDatagram");

		return valid;
	}

}
