import java.io.File;
import java.io.FileOutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketException;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.zip.CRC32;

public class Receiver {
	static int pkt_size = 1000;
	String message;

	public String getMessage() {
		return message;
	}

	// Receiver constructor
	public Receiver(int senderPort, int receiverPort) {
		DatagramSocket senderSocket, receiverSocket;
		System.out.println("Receiver: sender_port=" + senderPort + ", " + "receiver_port=" + receiverPort + ".");

		int prevSeqNum = -1; // previous sequence number received in-order
		int nextSeqNum = 0; // next expected sequence number
		boolean isTransferComplete = false; // (flag) if transfer is complete

		// create sockets
		try {
			senderSocket = new DatagramSocket(senderPort);
			receiverSocket = new DatagramSocket();
			System.out.println("Receiver: Listening");
			try {
				byte[] in_data = new byte[pkt_size]; // message data in packet
				DatagramPacket in_pkt = new DatagramPacket(in_data, in_data.length); // incoming packet
				InetAddress senderAddress = InetAddress.getByName("127.0.0.1");

				FileOutputStream fos = null;
				// make directory
				// path = ((path.substring(path.length()-1)).equals("/"))? path: path + "/"; //
				// append slash if missing
				// File filePath = new File(path);
				// if (!filePath.exists()) filePath.mkdir();

				// listen on sk2_dst_port
				while (!isTransferComplete) {
					// receive packet
					senderSocket.receive(in_pkt);
					String output = new String(in_pkt.getData());

					byte[] received_checksum = copyOfRange(in_data, 0, 8);
					CRC32 checksum = new CRC32();
					checksum.update(copyOfRange(in_data, 8, in_pkt.getLength()));
					byte[] calculated_checksum = ByteBuffer.allocate(8).putLong(checksum.getValue()).array();

					// if packet is not corrupted
					if (Arrays.equals(received_checksum, calculated_checksum)) {
						int seqNum = ByteBuffer.wrap(copyOfRange(in_data, 8, 12)).getInt();
						System.out.println("Receiver: Received sequence number: " + seqNum);

						// if packet received in order
						if (seqNum == nextSeqNum) {
							// no data packet = 28 bytes
							if (in_pkt.getLength() == 28) {
								byte[] ackPkt = generatePacket(-2); // construct teardown packet (ack -2)
								// send 20 acks in case last ack is not received by Sender (assures Sender
								// teardown)
								for (int i = 0; i < 20; i++) {
									receiverSocket
											.send(new DatagramPacket(ackPkt, ackPkt.length, senderAddress, senderPort));
								}
								isTransferComplete = true;
								System.out.println("Receiver: All packets received!");
								continue; // end listener
							}
							// send ack that a packet was received
							else {
								byte[] ackPkt = generatePacket(seqNum);
								receiverSocket
										.send(new DatagramPacket(ackPkt, ackPkt.length, senderAddress, receiverPort));
								System.out.println("Receiver: Sent Ack " + seqNum);
							}

							// if first packet of transfer
							if (seqNum == 0 && prevSeqNum == -1) {
								// Goes here the first time
								int messageLength = ByteBuffer.wrap(copyOfRange(in_data, 12, 16)).getInt(); // 0-8:checksum,
																											// 8-12:seqnum
								message = new String(copyOfRange(in_data, 16, 16 + messageLength)); // decode file name
								this.message = message;
								System.out.println(
										"Receiver: fileName length: " + messageLength + ", message:" + message);
								isTransferComplete = true;
								// Close it right away
								System.out.println("Receiver: All packets received!");
								continue; // end listener
							}

							// else if not first packet write to FileOutputStream
							else {
								String newPkt = new String(in_pkt.getData());
								message += newPkt;
							}

							nextSeqNum++; // update nextSeqNum
							prevSeqNum = seqNum; // update prevSeqNum
						}

						// if out of order packet received, send duplicate ack
						else {
							byte[] ackPkt = generatePacket(prevSeqNum);
							receiverSocket.send(new DatagramPacket(ackPkt, ackPkt.length, senderAddress, receiverPort));
							System.out.println("Receiver: Sent duplicate Ack " + prevSeqNum);
						}
					}

					// else packet is corrupted
					else {
						System.out.println("Receiver: Corrupt packet dropped");
						byte[] ackPkt = generatePacket(prevSeqNum);
						receiverSocket.send(new DatagramPacket(ackPkt, ackPkt.length, senderAddress, receiverPort));
						System.out.println("Receiver: Sent duplicate Ack " + prevSeqNum);
					}
				}
			} catch (Exception e) {
				e.printStackTrace();
				System.exit(-1);
			} finally {
				senderSocket.close();
				receiverSocket.close();
				System.out.println("Receiver: SenderSocket closed!");
				System.out.println("Receiver: ReceiverSocket closed!");
			}
		} catch (SocketException e1) {
			e1.printStackTrace();
		}
	}// END constructor

	// generate Ack packet
	public byte[] generatePacket(int ackNum) {
		byte[] ackNumBytes = ByteBuffer.allocate(4).putInt(ackNum).array();
		// calculate checksum
		CRC32 checksum = new CRC32();
		checksum.update(ackNumBytes);
		// construct Ack packet
		ByteBuffer pktBuf = ByteBuffer.allocate(12);
		pktBuf.put(ByteBuffer.allocate(8).putLong(checksum.getValue()).array());
		pktBuf.put(ackNumBytes);
		return pktBuf.array();
	}

	// same as Arrays.copyOfRange in 1.6
	public byte[] copyOfRange(byte[] srcArr, int start, int end) {
		int length = (end > srcArr.length) ? srcArr.length - start : end - start;
		byte[] destArr = new byte[length];
		System.arraycopy(srcArr, start, destArr, 0, length);
		return destArr;
	}

	// main function
	public static void main(String[] args) {
		// Ex: ports 4001 4002
		// follow same sequence for the sender constructor
		Receiver r = new Receiver(4001, 4002);
		// Get the message as such
		System.out.println(r.getMessage());
	}
}