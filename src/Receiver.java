import java.net.*;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.zip.CRC32;


public class Receiver {
	static int pktSize = 1000;
	private String message;
	String ip;
	
	public String getMessage() {
		return message;
	}

	public Receiver(String ip, int senderPort, int receiverPort) {
		DatagramSocket senderSocket, receiverSocket;
		this.ip = ip;
		
		int prevSeqNum = -1; // previous sequence number received in-order
		int nextSeqNum = 0; // next expected sequence number
		boolean isTransferComplete = false; // (flag) if transfer is complete

		// create sockets
		try {
			senderSocket = new DatagramSocket(senderPort);
			receiverSocket = new DatagramSocket();
			System.out.println("Receiver listening ...");
			try {
				byte[] inData = new byte[pktSize]; // message data in packet
				DatagramPacket inPkt = new DatagramPacket(inData, inData.length); // incoming packet
				InetAddress senderAddress = InetAddress.getByName(ip);

				while (!isTransferComplete) {
					// receive packet
					senderSocket.receive(inPkt);

					byte[] recChecksum = copyOfRange(inData, 0, 8);
					CRC32 checksum = new CRC32();
					checksum.update(copyOfRange(inData, 8, inPkt.getLength()));
					byte[] calcChecksum = ByteBuffer.allocate(8).putLong(checksum.getValue()).array();

					// if packet is not corrupted
					if (Arrays.equals(recChecksum, calcChecksum)) {
						int seqNum = ByteBuffer.wrap(copyOfRange(inData, 8, 12)).getInt();
						System.out.println("Received sequence number: " + seqNum);

						// if packet received in order
						if (seqNum == nextSeqNum) {
							// no data packet = 28 bytes
							if (inPkt.getLength() == 28) {
								byte[] ackPkt = generatePacket(-2);
								for (int i = 0; i < 20; i++) {
									receiverSocket
											.send(new DatagramPacket(ackPkt, ackPkt.length, senderAddress, senderPort));
								}
								isTransferComplete = true;
								System.out.println("All packets received!");
								continue; // end listener
							}
							// send ack that a packet was received
							else {
								byte[] ackPkt = generatePacket(seqNum);
								receiverSocket
										.send(new DatagramPacket(ackPkt, ackPkt.length, senderAddress, receiverPort));
								System.out.println("Sent Ack " + seqNum);
							}

							// if first packet of transfer
							if (seqNum == 0 && prevSeqNum == -1) {
								// Goes here the first time
								int messageLength = ByteBuffer.wrap(copyOfRange(inData, 12, 16)).getInt();
								message = new String(copyOfRange(inData, 16, 16 + messageLength)); // copy the message
								//System.out.println("Receiver: fileName length: " + messageLength + ", message:" + message);
								isTransferComplete = true;
								System.out.println("All packets received!");
								continue;
							}

							else {
								String newPkt = new String(inPkt.getData());
								message += newPkt;
							}

							nextSeqNum++; // update nextSeqNum
							prevSeqNum = seqNum; // update prevSeqNum
						}

						// if out of order packet received, send duplicate ack
						else {
							byte[] ackPkt = generatePacket(prevSeqNum);
							receiverSocket.send(new DatagramPacket(ackPkt, ackPkt.length, senderAddress, receiverPort));
							System.out.println("Sent duplicate Ack " + prevSeqNum);
						}
					}

					// Corrupted packet
					else {
						byte[] ackPkt = generatePacket(prevSeqNum);
						receiverSocket.send(new DatagramPacket(ackPkt, ackPkt.length, senderAddress, receiverPort));
						System.out.println("Sent duplicate Ack " + prevSeqNum);
					}
				}
			} catch (Exception e) {
				e.printStackTrace();
				System.exit(-1);
			} finally {
				senderSocket.close();
				receiverSocket.close();
				System.out.println("SenderSocket closed!");
				System.out.println("ReceiverSocket closed!");
			}
		} catch (SocketException e) {
			e.printStackTrace();
		}
	}

	// Send an acknowledgement packet
	public byte[] generatePacket(int ackNum) {
		byte[] ackNumBytes = ByteBuffer.allocate(4).putInt(ackNum).array();
		
		CRC32 checksum = new CRC32();
		checksum.update(ackNumBytes);
		
		ByteBuffer pktBuf = ByteBuffer.allocate(12);
		pktBuf.put(ByteBuffer.allocate(8).putLong(checksum.getValue()).array());
		pktBuf.put(ackNumBytes);
		return pktBuf.array();
	}

	// Returns the specified part of the byte array
	public byte[] copyOfRange(byte[] srcArr, int start, int end) {
		int length = (end > srcArr.length) ? srcArr.length - start : end - start;
		byte[] destArr = new byte[length];
		System.arraycopy(srcArr, start, destArr, 0, length);
		return destArr;
	}
	
	
	// ex main function
	public static void main(String[] args) {
		// Ex: ports 4001 4002
		// follow same sequence for the sender constructor
		Receiver r = new Receiver("127.0.0.1", 4001, 4002);
		// Get the message as such
		System.out.println(r.getMessage());
	}
}