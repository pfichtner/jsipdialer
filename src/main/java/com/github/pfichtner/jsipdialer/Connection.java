package com.github.pfichtner.jsipdialer;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.github.pfichtner.jsipdialer.messages.MessageReceived;
import com.github.pfichtner.jsipdialer.messages.MessageToSend;

public class Connection implements AutoCloseable {

	private static final Logger logger = Logger.getLogger(Connection.class.getName());

	final String sipServerAddress;
	final int sipServerPort;

	final String username;
	final String password;

	private final InetAddress serverAddress;
	private final DatagramSocket socket;

	public Connection(String sipServerAddress, int sipServerPort, String username, String password) throws Exception {
		this.sipServerAddress = sipServerAddress;
		this.sipServerPort = sipServerPort;
		this.username = username;
		this.password = password;

		this.serverAddress = InetAddress.getByName(sipServerAddress);
		this.socket = new DatagramSocket();
	}

	public void send(MessageToSend message) throws IOException {
		send(message.toString());
	}

	private void send(String message) throws IOException {
		logger.log(Level.INFO, () -> "Sending: " + message);
		byte[] sendData = message.getBytes();
		socket.send(new DatagramPacket(sendData, sendData.length, serverAddress, sipServerPort));
	}

	public MessageReceived receive() throws IOException {
		byte[] receiveData = new byte[1024];
		DatagramPacket receivePacket = new DatagramPacket(receiveData, receiveData.length);
		socket.receive(receivePacket);
		String response = new String(receivePacket.getData(), 0, receivePacket.getLength());
		logger.log(Level.INFO, () -> "Response from SIP Server:" + response);
		return MessageReceived.parse(response);
	}

	public int localPort() {
		return socket.getLocalPort();
	}

	public String localIpAddress() {
		return socket.getLocalAddress().getHostAddress();
	}

	@Override
	public void close() throws Exception {
		socket.close();
	}

}