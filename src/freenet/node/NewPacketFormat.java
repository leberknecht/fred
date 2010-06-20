package freenet.node;

import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.Vector;

import freenet.crypt.SHA256;
import freenet.io.comm.Message;
import freenet.io.comm.MessageCore;
import freenet.io.comm.Peer.LocalAddressException;
import freenet.support.Logger;
import freenet.support.LogThresholdCallback;
import freenet.support.SparseBitmap;

public class NewPacketFormat implements PacketFormat {

	public static final int MIN_MESSAGE_FRAGMENT_SIZE = 20;
	public static final int HMAC_LENGTH = 4;

	private static volatile boolean logMINOR;
	static {
		Logger.registerLogThresholdCallback(new LogThresholdCallback(){
			@Override
			public void shouldUpdate(){
				logMINOR = Logger.shouldLog(Logger.MINOR, this);
			}
		});
	}

	private PeerNode pn;
	private HashMap<Integer, MessageWrapper> started = new HashMap<Integer, MessageWrapper>();
	private final LinkedList<Long> acks = new LinkedList<Long>();
	private long nextSequenceNumber = 0;
	private HashMap<Long, SentPacket> sentPackets = new HashMap<Long, SentPacket>();
	private int nextMessageID = 0;

	private HashMap<Integer, byte[]> receiveBuffers = new HashMap<Integer, byte[]>();
	private HashMap<Integer, SparseBitmap> receiveMaps = new HashMap<Integer, SparseBitmap>();

	public NewPacketFormat(PeerNode pn) {
		this.pn = pn;
	}

	public void handleReceivedPacket(byte[] buf, int offset, int length, long now) {
		int packetStart = offset;

		//Check the hash
		//TODO: Decrypt hash first
		byte[] packetHash = new byte[HMAC_LENGTH];
		for (int i = 0; i < packetHash.length; i++) {
			packetHash[i] = buf[offset + i];
			buf[offset + i] = 0;
		}

		MessageDigest md = SHA256.getMessageDigest();
		md.update(buf, offset, length);
		byte[] hash = md.digest();

		for(int i = 0; i < packetHash.length; i++) {
			if(packetHash[i] != hash[i]) {
				Logger.warning(this, "Wrong hash for received packet. Discarding");
				return;
			}
		}

		offset += HMAC_LENGTH;

		// TODO: Decrypt
		byte[] plaintext = new byte[length - HMAC_LENGTH];
		System.arraycopy(buf, offset, plaintext, 0, (length - HMAC_LENGTH));
		offset = 0;

		long sequenceNumber = ((plaintext[offset] & 0xFF) << 24)
		                | ((plaintext[offset + 1] & 0xFF) << 16)
				| ((plaintext[offset + 2] & 0xFF) << 8)
				| (plaintext[offset + 3] & 0xFF);
		offset += 4;

		//Process received acks
		int numAcks = plaintext[offset++] & 0xFF;
		if(numAcks > 0) {
			long firstAck = 0;
			for (int i = 0; i < numAcks; i++) {
				long ack = 0;
				if(i == 0) {
					firstAck = ((plaintext[offset] & 0xFF) << 24)
					                | ((plaintext[offset + 1] & 0xFF) << 16)
							| ((plaintext[offset + 2] & 0xFF) << 8)
							| (plaintext[offset + 3] & 0xFF);
					offset += 4;

					if(logMINOR) Logger.minor(this, "First ack of received packet: " + firstAck);
				} else {
					ack = plaintext[offset++] & 0xFF;
				}

				SentPacket sent = null;
				synchronized(sentPackets) {
					sent = sentPackets.remove(firstAck + ack);
				}
				if(sent == null) {
					if(logMINOR) Logger.minor(this, "Received ack for unknown packet. Already acked?");
				} else {
					sent.acked();
				}
			}
		}

		//Handle received message fragments
		while(offset < plaintext.length) {
			boolean shortMessage = (plaintext[offset] & 0x80) != 0;
			boolean isFragmented = (plaintext[offset] & 0x40) != 0;
			boolean firstFragment = (plaintext[offset] & 0x20) != 0;
			int messageID = ((plaintext[offset] & 0x1F) << 8) | (plaintext[offset + 1] & 0xFF);
			offset += 2;

			int fragmentLength;
			if(shortMessage) {
				fragmentLength = plaintext[offset++];
			} else {
				fragmentLength = ((plaintext[offset] & 0xFF) << 8) | (plaintext[offset + 1] & 0xFF);
				offset += 2;
			}

			int messageLength = fragmentLength;
			int fragmentOffset = 0;
			if(isFragmented) {
				int value;
				if(shortMessage) {
					value = plaintext[offset++] & 0xFF;
				} else {
					value = ((plaintext[offset] & 0xFF) << 16) | ((plaintext[offset + 1] & 0xFF) << 8) | (plaintext[offset + 2] & 0xFF);
					offset += 3;
				}

				if(firstFragment) messageLength = value;
				else fragmentOffset = value;
			}

			byte[] recvBuf = receiveBuffers.get(messageID);
			SparseBitmap recvMap = receiveMaps.get(messageID);
			if(recvBuf == null) {
				if(!firstFragment) return; //For now we need the message length first

				if(messageLength < 0) {
					Logger.warning(this, "Message length(" + messageLength + ") is negative. Probably a bug. Discarding packet");
					return;
				} else {
					if(logMINOR) Logger.minor(this, "Creating buffer for messageID " + messageID + " of length " + messageLength);
				}
				recvBuf = new byte[messageLength];
				recvMap = new SparseBitmap();

				receiveBuffers.put(messageID, recvBuf);
				receiveMaps.put(messageID, recvMap);
			}

			System.arraycopy(plaintext, offset, recvBuf, fragmentOffset, fragmentLength);
			recvMap.add(fragmentOffset, fragmentOffset + fragmentLength - 1);
			offset += fragmentLength;

			if(recvMap.contains(0, recvBuf.length - 1)) {
				//TODO: If the other side resends a packet after we have gotten all the data, we will
				//never ack the resent packet
				receiveBuffers.remove(messageID);
				receiveMaps.remove(messageID);
				processFullyReceived(recvBuf);
			}
		}

		//Ack received packet
		synchronized(acks) {
			acks.addLast(sequenceNumber);
		}

	}

	public boolean maybeSendPacket(long now, Vector<ResendPacketItem> rpiTemp, int[] rpiIntTemp)
	                throws BlockedTooLongException {
		SentPacket sentPacket = new SentPacket();
		int offset = HMAC_LENGTH + 4; // HMAC, Sequence number (4)
		int minPacketSize = offset + 1; //Header length without any acks
		int maxPacketSize = pn.crypto.socket.getMaxPacketSize();
		byte[] packet = new byte[maxPacketSize];

		offset = insertAcks(packet, offset);

		// Try to finish Messages that have been started
		synchronized(started) {
			Iterator<MessageWrapper> it = started.values().iterator();
			while (it.hasNext() && (offset + MIN_MESSAGE_FRAGMENT_SIZE < maxPacketSize)) {
				MessageWrapper wrapper = it.next();
				offset = insertFragment(packet, offset, maxPacketSize, wrapper, sentPacket);
			}
		}

		// Add messages from message queue
		PeerMessageQueue messageQueue = pn.getMessageQueue();
		while (offset + MIN_MESSAGE_FRAGMENT_SIZE < maxPacketSize) {
			MessageItem item = null;
			synchronized(messageQueue) {
				item = messageQueue.grabQueuedMessageItem();
			}
			if(item == null) break;

			int messageID = getMessageID();
			if(messageID == -1) {
				if(logMINOR) Logger.minor(this, "No availiable message ID, requeuing and sending packet");
				messageQueue.pushfrontPrioritizedMessageItem(item);
				break;
			}
			MessageWrapper wrapper = new MessageWrapper(item, messageID);

			offset = insertFragment(packet, offset, maxPacketSize, wrapper, sentPacket);
			synchronized(started) {
				started.put(messageID, wrapper);
			}
		}

		if(offset == minPacketSize) {
			return false;
		}

		byte[] data = new byte[offset];
		System.arraycopy(packet, 0, data, 0, data.length);

		//Add sequence number
		long sequenceNumber = nextSequenceNumber++;
		data[HMAC_LENGTH] = (byte) (sequenceNumber >>> 24);
		data[HMAC_LENGTH + 1] = (byte) (sequenceNumber >>> 16);
		data[HMAC_LENGTH + 2] = (byte) (sequenceNumber >>> 8);
		data[HMAC_LENGTH + 3] = (byte) (sequenceNumber);

		//TODO: Encrypt

		//Add hash
		//TODO: Encrypt this to make a HMAC
		MessageDigest md = SHA256.getMessageDigest();
		byte[] hash = md.digest(data);

		System.arraycopy(hash, 0, data, 0, HMAC_LENGTH);

		try {
	                pn.crypto.socket.sendPacket(data, pn.getPeer(), pn.allowLocalAddresses());
                } catch (LocalAddressException e) {
	                Logger.error(this, "Caught exception while sending packet", e);
			return false;
                }

		synchronized(sentPackets) {
			sentPackets.put(sequenceNumber, sentPacket);
		}
		return true;
	}
	
	private int insertAcks(byte[] packet, int offset) {
		int numAcks = 0;
		int numAcksOffset = offset++;

		// Insert acks
		synchronized(acks) {
			long firstAck = 0;
			Iterator<Long> it = acks.iterator();
			while (it.hasNext() && numAcks < 256) {
				long ack = it.next();
				if(numAcks == 0) {
					if(logMINOR) Logger.minor(this, "First ack in packet to send is " + ack);

					firstAck = ack;

					packet[offset++] = (byte) (ack >>> 24);
					packet[offset++] = (byte) (ack >>> 16);
					packet[offset++] = (byte) (ack >>> 8);
					packet[offset++] = (byte) (ack);
				} else {
					// Compress if possible
					long compressedAck = ack - firstAck;
					if((compressedAck < 0) || (compressedAck > 255)) {
						// TODO: If less that 0, we could replace firstAck
						continue;
					}

					packet[offset++] = (byte) (compressedAck);
				}
				if(logMINOR) Logger.minor(this, "Adding ack for packet " + ack);

				++numAcks;
				it.remove();
			}
		}
		
		packet[numAcksOffset] = (byte) numAcks;

		return offset;
	}

	private int insertFragment(byte[] packet, int offset, int maxPacketSize, MessageWrapper wrapper, SentPacket sent) {
		boolean isFragmented = wrapper.isFragmented(maxPacketSize - offset - 7); //7 is the maximum header length
		boolean firstFragment = wrapper.isFirstFragment();
		
		// Insert data
		int dataOffset = offset
		                + 2 //Message id + flags
		                + (wrapper.isLongMessage() ? 2 : 1) //Fragment length
		                + (isFragmented ? (wrapper.isLongMessage() ? 3 : 1) : 0); //Fragment offset or message length
		int[] fragmentInfo = wrapper.getData(packet, dataOffset, maxPacketSize - dataOffset);
		int dataLength = fragmentInfo[0];
		int fragmentOffset = fragmentInfo[1];

		if(dataLength == 0) return offset;

		// Add messageID and flags
		int messageID = wrapper.getMessageID();
		if(messageID != (messageID & 0x7FFF)) {
			Logger.error(this, "MessageID was " + messageID + ", masked is: " + (messageID & 0x7FFF));
		}
		messageID = messageID & 0x7FFF; // Make sure the flag bits are 0

		if(!wrapper.isLongMessage()) messageID = messageID | 0x8000;
		if(isFragmented) messageID = messageID | 0x4000;
		if(firstFragment) messageID = messageID | 0x2000;

		packet[offset++] = (byte) (messageID >>> 8);
		packet[offset++] = (byte) (messageID);

		// Add fragment length, 2 bytes if long message
		if(wrapper.isLongMessage()) packet[offset++] = (byte) (dataLength >>> 8);
		packet[offset++] = (byte) (dataLength);

		if(isFragmented) {
			// If firstFragment is true, add total message length. Else, add fragment offset
			int value = firstFragment ? wrapper.getLength() : fragmentOffset;

			if(wrapper.isLongMessage()) {
				packet[offset++] = (byte) (value >>> 16);
				packet[offset++] = (byte) (value >>> 8);
			}
			packet[offset++] = (byte) (value);
		}

		offset += dataLength;
		sent.addFragment(wrapper, fragmentOffset, fragmentOffset + dataLength - 1);

		return offset;
	}

	private int getMessageID() {
		int messageID = nextMessageID;

		synchronized(started) {
			if(started.containsKey(messageID)) return -1;
		}

		nextMessageID = (nextMessageID + 1) % 8192;
		return messageID;
	}

	private void processFullyReceived(byte[] buf) {
		MessageCore core = pn.node.usm;
		Message m = core.decodeSingleMessage(buf, 0, buf.length, pn, 0);
		if(m != null) {
			core.checkFilters(m, pn.crypto.socket);
		}
	}

	private class SentPacket {
		LinkedList<MessageWrapper> messages = new LinkedList<MessageWrapper>();
		LinkedList<int[]> ranges = new LinkedList<int[]>();

		public void addFragment(MessageWrapper source, int start, int end) {
			messages.add(source);
			ranges.add(new int[] { start, end });
		}

		public void acked() {
			Iterator<MessageWrapper> msgIt = messages.iterator();
			Iterator<int[]> rangeIt = ranges.iterator();

			while(msgIt.hasNext()) {
				MessageWrapper wrapper = msgIt.next();
				int[] range = rangeIt.next();
				if(wrapper.ack(range[0], range[1])) {
					synchronized(started) {
						started.remove(wrapper);
					}
				}
			}
		}
	}

}
