package TFTPServer;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;

public class TFTPServer {
	public static final int TFTPPORT = 4970;
	public static final int BUFSIZE = 516;
	public static final String READDIR = ".\\library\\read\\"; // custom address at your PC
	public static final String WRITEDIR = ".\\library\\read\\"; // custom address at your PC
	// OP codes
	public static final int OP_RRQ = 1;
	public static final int OP_WRQ = 2;
	public static final int OP_DAT = 3;
	public static final int OP_ACK = 4;
	public static final int OP_ERR = 5;

	public static void main(String[] args) throws IOException {
		if (args.length > 0) {
			System.err.printf("usage: java %s\n", TFTPServer.class.getCanonicalName());
			System.exit(1);
		}
		// Starting the server
		try {
			TFTPServer server = new TFTPServer();
			server.start();
		} catch (SocketException e) {
			e.printStackTrace();
		}
	}

	private void start() throws IOException {
		byte[] buf = new byte[BUFSIZE];

		// Create socket
		DatagramSocket socket = new DatagramSocket(null);

		// Create local bind point
		SocketAddress localBindPoint = new InetSocketAddress(TFTPPORT);
		socket.bind(localBindPoint);

		System.out.printf("Listening at port %d for new requests\n", TFTPPORT);

		// Loop to handle client requests
		while (true) {

			final InetSocketAddress clientAddress = receiveFrom(socket, buf);

			// If clientAddress is null, an error occurred in receiveFrom()
			if (clientAddress == null)
				continue;

			final StringBuffer requestedFile = new StringBuffer();
			final int reqtype = ParseRQ(buf, requestedFile);
			System.out.println("Opcode:" + reqtype);

			new Thread() {
				public void run() {
					try {
						DatagramSocket sendSocket = new DatagramSocket(0);

						// Connect to client
						sendSocket.connect(clientAddress);

						System.out.printf("%s request for %s from %s using port %d\n",
								(reqtype == OP_RRQ) ? "Read" : "Write", clientAddress.getHostName(),
								clientAddress.getPort());

						// Read request
						if (reqtype == OP_RRQ) {
							requestedFile.insert(0, READDIR);
							HandleRQ(sendSocket, requestedFile.toString(), OP_RRQ);
						}
						// Write request
						else {
							requestedFile.insert(0, WRITEDIR);
							HandleRQ(sendSocket, requestedFile.toString(), OP_WRQ);
						}
						sendSocket.close();
					} catch (SocketException e) {
						e.printStackTrace();
					}
				}
			}.start();
		}
	}

	/**
	 * Reads the first block of data, i.e., the request for an action (read or
	 * write).
	 * 
	 * @param socket
	 *            (socket to read from)
	 * @param buf
	 *            (where to store the read data)
	 * @return socketAddress (the socket address of the client)
	 * @throws IOException
	 */
	private InetSocketAddress receiveFrom(DatagramSocket socket, byte[] buf) throws IOException {
		// Create datagram packet
		DatagramPacket datagramP = new DatagramPacket(buf, buf.length);
		// Receive packet
		socket.receive(datagramP);
		// Get client address and port from the packet
		InetSocketAddress socketAddress = new InetSocketAddress(datagramP.getAddress(), datagramP.getPort());

		return socketAddress;
	}

	/**
	 * Parses the request in buf to retrieve the type of request and requestedFile
	 * 
	 * @param buf
	 *            (received request)
	 * @param requestedFile
	 *            (name of file to read/write)
	 * @return opcode (request type: RRQ or WRQ)
	 */
	private int ParseRQ(byte[] buf, StringBuffer requestedFile) {
		ByteBuffer wrap = ByteBuffer.wrap(buf);
		short opcode = wrap.getShort();

		// Finding the file name
		int readBytes = -1;
		int x = 1;
		do {
			x++;
		} while (buf[x] != 0);
		System.out.println("X is: " + x);
		if (buf[x] == 0) {
			readBytes = x;
		} else {
			System.err.print("Error in request! Exiting..");
			System.exit(1);
		}
		String fileName = new String(buf, 2, readBytes - 2);
		System.out.println("Filename: " + fileName);
		requestedFile.append(fileName);

		// Finding the mode
		int y = readBytes;
		do {
			y++;
			if (buf[y] == 0) {
				String mode = new String(buf, readBytes + 1, y - (readBytes + 1)).toLowerCase();
				System.out.println("Transfer mode = " + mode);
				if (mode.equals("octet")) {
					return opcode;
				} else {
					System.err.println("Incorrect or non existant mode!");
					System.exit(1);
				}
			}
		} while (y < buf.length);
		System.out.println("Fucked up!");
		return opcode;
	}

	/**
	 * Handles RRQ and WRQ requests
	 * 
	 * @param sendSocket
	 *            (socket used to send/receive packets)
	 * @param requestedFile
	 *            (name of file to read/write)
	 * @param opcode
	 *            (RRQ or WRQ)
	 */
	private void HandleRQ(DatagramSocket sendSocket, String requestedFile, int opcode) {

		File file = new File(requestedFile);
		byte[] buf = new byte[BUFSIZE - 4]; // -4 since the header is already done
		ByteBuffer buffer;
		DatagramPacket sender;
		FileInputStream read = null;

		if (opcode == OP_RRQ) {

			try {
				read = new FileInputStream(file);
			} catch (FileNotFoundException err) {
				System.out.println("Error! File was not found!");
			}

			short blockNumber = 1;

			while (true) {
				int length = 0;
				try {
					length = read.read(buf);
				} catch (Exception e) {
					System.out.println("Error! Could not read file!");
				}

				if (length == -1) {
					length = 0;
				}
				buffer = ByteBuffer.allocate(BUFSIZE);
				buffer.putShort(blockNumber);
				buffer.put(buf, 0, length);
				sender = new DatagramPacket(buffer.array(), length + 4);
				boolean result = send_DATA_receive_ACK(sendSocket, sender, blockNumber++);

				if (result) {
					System.out.println("Successfully sent. Block Number = " + blockNumber);
				} else {
					System.out.println("Error! Connection has been lost");
					return;
				}
			}
			// See "TFTP Formats" in TFTP specification for the DATA and ACK packet contents

			// } else if (opcode == OP_WRQ) {

			// boolean result = receive_DATA_send_ACK(params);
			// } else {
			// System.err.println("Invalid request. Sending an error packet.");
			// See "TFTP Formats" in TFTP specification for the ERROR packet contents
			// send_ERR(params);

		}
	}

	/**
	 * To be implemented
	 */
	private boolean send_DATA_receive_ACK(DatagramSocket soc, DatagramPacket send, short blockID) {
		int retry = 0;
		byte[] recieve = new byte[BUFSIZE];
		DatagramPacket recieved = new DatagramPacket(recieve, recieve.length);
		short ack;

		while (true) {
			if (retry >= 6) {
				System.err.println("Error! Program has timed out and is now closing.");
				return false;
			}

			try {
				soc.send(send);
				System.out.println("Packet has been sent!");
				soc.receive(recieved);

				ByteBuffer ackBuffer = ByteBuffer.wrap(recieved.getData());
				short opcode = ackBuffer.getShort();

				if (opcode == OP_ERR) {
					System.err.println("Error! Something went wrong! Closing Connection.");
					ack = -1;
				} else {
					ack = ackBuffer.getShort();
				}

				if (ack == blockID) {
					return true;
				} else if (ack == -1) {
					return false;
				}

			} catch (SocketTimeoutException e1) {
				System.out.println("Socket timed out!");
			} catch (IOException e2) {

			}
		}
	}
	//
	// private boolean receive_DATA_send_ACK(params)
	// {return true;}
	//
	// private void send_ERR(params)
	// {}

}
