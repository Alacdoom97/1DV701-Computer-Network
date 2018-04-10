package TFTPServer;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
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
	public static final int BUFSIZE = 600;
	public static final String READDIR = "src\\"; // custom address at your PC
	public static final String WRITEDIR = "src\\"; // custom address at your PC
	// OP codes
	public static final int OP_RRQ = 1;
	public static final int OP_WRQ = 2;
	public static final int OP_DAT = 3;
	public static final int OP_ACK = 4;
	public static final int OP_ERR = 5;
	public static final int ERR_NOTDEFINED = 0;
	public static final int ERR_FILENOTFOUND = 1;
	public static final int ERR_ACCESS = 2;
	public static final int ERR_EXISTS = 6;

	public static final String[] errorMessages = { "Not Defined!", "File not found!", "File already exists!",
			"Access Violation!" };

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
			System.out.println("Opcode:" + reqtype + "\n");

			new Thread() {
				public void run() {
					try {
						DatagramSocket sendSocket = new DatagramSocket(0);

						// Connect to client
						sendSocket.connect(clientAddress);

						System.out.printf("%s request for %s from %s using port %d \n",
								(reqtype == OP_RRQ) ? "Read" : "Write", requestedFile.toString(),
								clientAddress.getHostName(), clientAddress.getPort());
						System.out.println("");
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
		System.out.println("Something went wrong!");
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
		System.out.println("File: " + requestedFile);
		File file = new File(requestedFile);
		byte[] buf = new byte[BUFSIZE - 4]; // -4 since the header is already done
		ByteBuffer buffer;
		DatagramPacket sender;
		FileInputStream read = null;

		if (opcode == OP_RRQ) {

			try {
				read = new FileInputStream(file);
			} catch (FileNotFoundException err) {
				System.out.println("Error! Remote File was not found!");
				send_ERR(sendSocket, "File not found!", (short) ERR_FILENOTFOUND);
				return;
			}

			short blockNumber = 1;

			while (true) {
				int length = 0;
				try {
					length = read.read(buf);
				} catch (Exception e) {
					System.out.println("Error! Could not read file!");
					send_ERR(sendSocket, "Could not read file!", (short) ERR_ACCESS);
				}

				if (length == -1) {
					length = 0;
				}
				buffer = ByteBuffer.allocate(BUFSIZE);
				buffer.putShort((short) OP_DAT);
				buffer.putShort(blockNumber);
				buffer.put(buf, 0, length);
				sender = new DatagramPacket(buffer.array(), length + 4);
				boolean result = send_DATA_receive_ACK(sendSocket, sender, blockNumber++);

				if (result) {
					System.out.println("Successfully sent. Block Number = " + blockNumber + "\n");
				} else {
					System.out.println("Error! Connection has been lost");
					send_ERR(sendSocket, "Lost Connection!", (short) ERR_NOTDEFINED);
					System.exit(1);
				}

				if (length < 512) {
					try {
						read.close();
					} catch (IOException e) {
						System.err.println("Trouble closing file.");
					}
					break;
				}
			}
			// See "TFTP Formats" in TFTP specification for the DATA and ACK packet contents

		} else if (opcode == OP_WRQ) {
			if (file.exists()) {
				send_ERR(sendSocket, "File already exists!", (short) ERR_EXISTS);
				return;
			}

			short blockNumber = 0;

			FileOutputStream output;

			try {
				output = new FileOutputStream(file);
			} catch (FileNotFoundException e) {
				e.printStackTrace();
				send_ERR(sendSocket, "Could not create file! Access Denied", (short) ERR_ACCESS);
				System.out.println("Error! File was not found!");
				return;
			}

			while (true) {
				DatagramPacket dataPack = receive_DATA_send_ACK(sendSocket, ack(blockNumber++), blockNumber);
				if (dataPack == null) {
					System.err.println("There has been some form of error and connection hs been lost");
					send_ERR(sendSocket, "Connection has been lost", (short) ERR_ACCESS);
				} else {
					byte[] data = dataPack.getData();
					try {
						output.write(data, 0, dataPack.getLength() - 4);
					} catch (IOException e) {
						System.out.println("IOException error while writing!");
						send_ERR(sendSocket, "Couldn't write data to file!", (short) ERR_ACCESS);
						System.exit(1);
					}

					if (dataPack.getLength() - 4 < 512) {
						try {
							sendSocket.send(ack(blockNumber));
						} catch (IOException e1) {
							try {
								sendSocket.send(ack(blockNumber)); // Trying again
							} catch (IOException e2) {
								System.out.println("IOException again!");
								System.exit(1);
							}
						}
					}

					try {
						output.close();
					} catch (IOException e) {
						System.out.println("Error while closing file!");
					}
					break;
				}
			}

		} else {
			System.err.println("Invalid request. Sending an error packet.");
			System.exit(1);
		}
	}

	/**
	 * To be implemented
	 */
	private boolean send_DATA_receive_ACK(DatagramSocket soc, DatagramPacket send, short blockID) {
		byte[] recieve = new byte[BUFSIZE];
		DatagramPacket recieved = new DatagramPacket(recieve, recieve.length);
		short ack;

		while (true) {
			try {
				soc.send(send);
				System.out.println("Packet has been sent!");
				soc.setSoTimeout(5000); // 5000ms
				soc.receive(recieved);

				ByteBuffer ackBuffer = ByteBuffer.wrap(recieved.getData());
				short opcode = ackBuffer.getShort();
				System.out.println("Opcode: " + opcode + "(Should be 4, ACK!)");
				if (opcode == OP_ERR) {
					System.err.println("Error! Something went wrong! Closing Connection.");
					parseError(ackBuffer);
					ack = -1;
				} else {
					ack = ackBuffer.getShort();
				}

				if (ack == blockID) {
					return true;
				} else if (ack == -1) {
					return false;
				} else {
					throw new SocketTimeoutException();
				}

			} catch (SocketTimeoutException e1) {
				System.err.println("Socket timed out!");
				break;
			} catch (IOException e2) {
				System.err.println("IO Exception!");
				break;
			}
		}
		return false;
	}

	private DatagramPacket receive_DATA_send_ACK(DatagramSocket soc, DatagramPacket send, short blockID) {
		byte[] recieve = new byte[BUFSIZE];
		DatagramPacket recieved = new DatagramPacket(recieve, recieve.length);
		short block;

		while (true) {
			try {
				soc.send(send);
				System.out.println("Packet has been sent!");
				soc.setSoTimeout(5000); // 5000ms
				soc.receive(recieved);

				ByteBuffer ackBuffer = ByteBuffer.wrap(recieved.getData());
				short opcode = ackBuffer.getShort();
				System.out.println("Opcode: " + opcode);
				if (opcode == OP_ERR) {
					System.err.println("Error! Something went wrong! Closing Connection.");
					parseError(ackBuffer);
					return null;
				} else {
					block = ackBuffer.getShort();
				}

				if (block == blockID) {
					return recieved;
				} else if (block == -1) {
					return null;
				} else {
					throw new SocketTimeoutException();
				}

			} catch (SocketTimeoutException e1) {
				System.err.println("Socket timed out!");

			} catch (IOException e) {
				System.err.println("IO Exception!");
			} finally {

				try {
					soc.setSoTimeout(0);
				} catch (SocketException e) {
					System.err.println("Error on Timeout.");
				}
			}
		}
	}

	private DatagramPacket ack(short blockNum) {
		ByteBuffer buff = ByteBuffer.allocate(BUFSIZE);
		buff.putShort((short) OP_ACK);
		buff.putShort(blockNum);
		return new DatagramPacket(buff.array(), 4);
	}

	private void parseError(ByteBuffer bytes) {
		short code = bytes.getShort();

		byte[] buffer = bytes.array();
		for (int i = 4; i < buffer.length; i++) {
			if (buffer[i] == 0) {
				String message = new String(buffer, 4, i - 4);
				if (code > 7) {
					code = 0;
				}
				System.err.println(errorMessages[code] = ": " + message);
				break;
			}
		}
	}

	private void send_ERR(DatagramSocket sendSocket, String message, short code) {

		ByteBuffer errorCode = ByteBuffer.allocate(BUFSIZE);
		errorCode.putShort((short) OP_ERR);
		errorCode.putShort(code);
		errorCode.put(message.getBytes());
		errorCode.put((byte) 0);

		DatagramPacket errorPacket = new DatagramPacket(errorCode.array(), errorCode.array().length);

		try {
			sendSocket.send(errorPacket);
		} catch (IOException e) {
			System.err.println("File was not able to be sent!");
			e.printStackTrace();
		}
	}
}
