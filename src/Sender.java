import java.net.*;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Timer;
import java.util.Vector;
import java.util.concurrent.Semaphore;
import java.util.zip.CRC32;


// The following implementation uses the Go-Back-N protocol
public class Sender {
	static int dataSize = 988;
	static int winSize = 10;
	static int timeoutVal = 300; // 300ms until timeout

	int base;
	int nextSeqNum;
	String message;
	Vector<byte[]> packetsList;
	Timer timer;
	Semaphore s;
	boolean isTransferComplete;
	String ip;

	public Sender(String ip, int ackPort, int broadPort, String message) {
		base = 0;
		nextSeqNum = 0;
		this.message = message;
		this.ip = ip;
		packetsList = new Vector<byte[]>(winSize);
		isTransferComplete = false;
		DatagramSocket ackSocket, broadSocket;
		s = new Semaphore(1);

		try {
			ackSocket = new DatagramSocket();
			broadSocket = new DatagramSocket(broadPort);

			AcknowledgeThread ackThread = new AcknowledgeThread(broadSocket);
			BroadcastThread broadThread = new BroadcastThread(ackSocket, ackPort, broadPort);
			ackThread.start();
			broadThread.start();

		} catch (Exception e) {
			e.printStackTrace();
			System.exit(-1);
		}
	}

	public byte[] copyOfRange(byte[] srcArr, int start, int end) {
		int length = (end > srcArr.length) ? srcArr.length - start : end - start;
		byte[] destArr = new byte[length];
		System.arraycopy(srcArr, start, destArr, 0, length);
		return destArr;
	}


	public class BroadcastThread extends Thread {
		private DatagramSocket skOut;
		private int destPort;
		private InetAddress destAddr;

		public BroadcastThread(DatagramSocket sk_out, int dst_port, int recv_port) {
			this.skOut = sk_out;
			this.destPort = dst_port;
		}

		// constructs the packet prepended with header information
		public byte[] generatePacket(int seqNum, byte[] dataBytes) {
			byte[] seqNumBytes = ByteBuffer.allocate(4).putInt(seqNum).array();

			// generate checksum
			CRC32 checksum = new CRC32();
			checksum.update(seqNumBytes);
			checksum.update(dataBytes);
			byte[] checksumBytes = ByteBuffer.allocate(8).putLong(checksum.getValue()).array(); // checksum (8 bytes)

			// generate packet
			ByteBuffer pktBuf = ByteBuffer.allocate(8 + 4 + dataBytes.length);
			pktBuf.put(checksumBytes);
			pktBuf.put(seqNumBytes);
			pktBuf.put(dataBytes);
			return pktBuf.array();
		}

		public void run() {
			try {
				destAddr = InetAddress.getByName(ip);

				try {
					// while there are still packets yet to be received by receiver
					while (!isTransferComplete) {
						// send packets if window is not yet full
						if (nextSeqNum < base + winSize) {

							s.acquire(); /***** enter CS *****/

							byte[] outData = new byte[10];
							boolean isFinalSeqNum = false;

							// if packet is in packetsList, retrieve from list
							if (nextSeqNum < packetsList.size()) {
								outData = packetsList.get(nextSeqNum);
							}
							// else construct packet and add to list
							else {
								// if first packet, special handling: prepend file information
								if (nextSeqNum == 0) {
									byte[] messageBytes = message.getBytes();
									byte[] messageLengthBytes = ByteBuffer.allocate(4).putInt(message.length()).array();
									
									ByteBuffer BB = ByteBuffer.allocate(4 + messageBytes.length);
									BB.put(messageLengthBytes);
									BB.put(messageBytes);
									outData = generatePacket(nextSeqNum, BB.array());
								}
								// else if subsequent packets
								else {
									byte[] messageBytes = message.getBytes();
									outData = generatePacket(nextSeqNum, messageBytes);
								}
							}

							// send the packet
							skOut.send(new DatagramPacket(outData, outData.length, destAddr, destPort));
							System.out.println("Sent seqNum " + nextSeqNum);

							// update nextSeqNum if currently not at FinalSeqNum
							if (!isFinalSeqNum)
								nextSeqNum++;
							s.release(); /***** leave CS *****/
						}
						sleep(5);
					}
				} catch (Exception e) {
					e.printStackTrace();
				} finally {
					skOut.close();
					System.out.println("skOut closed!");
				}
			} catch (Exception e) {
				e.printStackTrace();
				System.exit(-1);
			}
		}
	}


	public class AcknowledgeThread extends Thread {
		private DatagramSocket skIn;

		public AcknowledgeThread(DatagramSocket sk_in) {
			this.skIn = sk_in;
		}

		// returns -1 if corrupted, else return Ack number
		int decodePacket(byte[] pkt) {
			byte[] received_checksumBytes = copyOfRange(pkt, 0, 8);
			byte[] ackNumBytes = copyOfRange(pkt, 8, 12);
			CRC32 checksum = new CRC32();
			checksum.update(ackNumBytes);
			byte[] calculated_checksumBytes = ByteBuffer.allocate(8).putLong(checksum.getValue()).array();// checksum (8
																											// bytes)
			if (Arrays.equals(received_checksumBytes, calculated_checksumBytes))
				return ByteBuffer.wrap(ackNumBytes).getInt();
			else
				return -1;
		}

		// receiving process (updates base)
		public void run() {
			try {
				byte[] in_data = new byte[12]; // ack packet with no data
				DatagramPacket in_pkt = new DatagramPacket(in_data, in_data.length);
				try {
					// while there are still packets yet to be received by receiver
					while (!isTransferComplete) {

						skIn.receive(in_pkt);
						int ackNum = decodePacket(in_data);
						System.out.println("Received Ack " + ackNum);

						// if ack is not corrupted
						if (ackNum != -1) {
							// if duplicate ack
							if (base == ackNum + 1) {
								s.acquire(); /***** enter CS *****/
								nextSeqNum = base; // resets nextSeqNum
								s.release(); /***** leave CS *****/
							}
							// else if teardown ack
							else if (ackNum == 0)
							// Transfer complete
							{
								isTransferComplete = true;
								// send final ack
							}
							// else normal ack
							else {
								base = ackNum++; // update base number
								s.acquire(); /***** enter CS *****/
								// else packet acknowledged, restart timer
								s.release(); /***** leave CS *****/
							}
						}
						// else if ack corrupted, do nothing
					}
				} catch (Exception e) {
					e.printStackTrace();
				} finally {
					skIn.close();
					System.out.println("sk_in closed!");
				}
			} catch (Exception e) {
				e.printStackTrace();
				System.exit(-1);
			}
		}
	}
	
	public static void main(String[] args) {
		// sender port, receiver port, message
		// ex: 4001 4002 "hello"
		String s = "";
		new Sender("127.0.0.1", 4001, 4002, s);
	}
}