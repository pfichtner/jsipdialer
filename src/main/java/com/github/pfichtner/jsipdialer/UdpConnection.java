package com.github.pfichtner.jsipdialer;

import static java.util.logging.Level.INFO;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.util.logging.Logger;

import com.github.pfichtner.jsipdialer.messages.MessageReceived;
import com.github.pfichtner.jsipdialer.messages.MessageToSend;

public class UdpConnection implements Connection, AutoCloseable {

	private static final Logger logger = Logger.getLogger(UdpConnection.class.getName());

	private final String sipServerAddress;
	private final InetAddress serverAddress;
	private final int sipServerPort;
	private final DatagramSocket socket;

	public UdpConnection(String sipServerAddress, int sipServerPort) throws UnknownHostException, SocketException {
		this.sipServerAddress = sipServerAddress;
		this.serverAddress = InetAddress.getByName(sipServerAddress);
		this.sipServerPort = sipServerPort;
		this.socket = new DatagramSocket();
	}

	@Override
	public void send(MessageToSend message) throws IOException {
		send(message.toString());
	}

	private void send(String message) throws IOException {
		logger.log(INFO, () -> "Sending: " + message);
		byte[] sendData = message.getBytes();
		socket.send(new DatagramPacket(sendData, sendData.length, serverAddress, sipServerPort));
	}

	@Override
	public MessageReceived receive() throws IOException {
		byte[] receiveData = new byte[1024];
		DatagramPacket receivePacket = new DatagramPacket(receiveData, receiveData.length);
		socket.receive(receivePacket);
		String response = new String(receivePacket.getData(), 0, receivePacket.getLength());
		logger.log(INFO, () -> "Response from SIP Server:" + response);
		return MessageReceived.parse(response);
	}

	@Override
	public String remoteServerAddress() {
		return sipServerAddress;
	}

	@Override
	public int localPort() {
		return socket.getLocalPort();
	}

	@Override
	public void close() throws Exception {
		socket.close();
	}

}