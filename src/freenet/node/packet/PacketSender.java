/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.node.packet;

import java.util.ArrayList;
import java.util.HashSet;

import freenet.clients.http.ExternalLinkToadlet;
import freenet.io.comm.Peer;
import freenet.l10n.NodeL10n;
import freenet.node.BlockedTooLongException;
import freenet.node.Node;
import freenet.node.NodeStats;
import freenet.node.OpennetManager;
import freenet.node.PeerManager;
import freenet.node.PeerNode;
import freenet.node.useralerts.AbstractUserAlert;
import freenet.node.useralerts.UserAlert;
import freenet.support.HTMLNode;
import freenet.support.LogThresholdCallback;
import freenet.support.Logger;
import freenet.support.Logger.LogLevel;
import freenet.support.OOMHandler;
import freenet.support.TimeUtil;
import freenet.support.io.NativeThread;
import freenet.support.math.MersenneTwister;

/**
 * @author amphibian
 *
 *         Thread that sends a packet whenever: - A packet needs to be resent immediately -
 *         Acknowledgments or resend requests need to be sent urgently.
 */
// j16sdiz (22-Dec-2008):
// FIXME this is the only class implements Ticker, everbody is using this as
// a generic task scheduler. Either rename this class, or create another tricker for non-Packet tasks
public class PacketSender implements Runnable {

	private static volatile boolean logMINOR;
	private static volatile boolean logDEBUG;

	static {
		Logger.registerLogThresholdCallback(new LogThresholdCallback(){
			@Override
			public void shouldUpdate(){
				logMINOR = Logger.shouldLog(LogLevel.MINOR, this);
				logDEBUG = Logger.shouldLog(LogLevel.DEBUG, this);
			}
		});
	}

	/** Maximum time we will queue a message for in milliseconds */
	public static final int MAX_COALESCING_DELAY = 100;
	/** Maximum time we will queue a message for in milliseconds if it is bulk data.
	 * Note that we will send the data immediately anyway because it will normally be big
	 * enough to send a full packet. However this impacts the choice of whether to send
	 * realtime or bulk data, see PeerMessageQueue.addMessages(). */
	public static final int MAX_COALESCING_DELAY_BULK = 5000;
	/** If opennet is enabled, and there are fewer than this many connections,
	 * we MAY attempt to contact old opennet peers (opennet peers we have
	 * dropped from the routing table but kept around in case we can't connect). */
	static final int MIN_CONNECTIONS_TRY_OLD_OPENNET_PEERS = 5;
	/** We send connect attempts to old-opennet-peers no more than once every
	 * this many milliseconds. */
	static final int MIN_OLD_OPENNET_CONNECT_DELAY_NO_CONNS = 10 * 1000;
	/** We send connect attempts to old-opennet-peers no more than once every
	 * this many milliseconds. */
	static final int MIN_OLD_OPENNET_CONNECT_DELAY = 60 * 1000;
	final NativeThread myThread;
	final Node node;
	NodeStats stats;
	long lastReportedNoPackets;
	long lastReceivedPacketFromAnyNode;
	private MersenneTwister localRandom;

	public PacketSender(Node node) {
		this.node = node;
		myThread = new NativeThread(this, "PacketSender thread for " + node.getDarknetPortNumber(), NativeThread.MAX_PRIORITY, false);
		myThread.setDaemon(true);
		localRandom = node.createRandom();
	}

	public void start(NodeStats stats) {
		this.stats = stats;
		Logger.normal(this, "Starting PacketSender");
		System.out.println("Starting PacketSender");
		myThread.start();
	}

	private void schedulePeriodicJob() {
		
		node.ticker.queueTimedJob(new Runnable() {

			@Override
			public void run() {
				try {
					long now = System.currentTimeMillis();
					if (logMINOR)
						Logger.minor(PacketSender.class,
								"Starting shedulePeriodicJob() at " + now);
					PeerManager pm = node.peers;
					pm.maybeLogPeerNodeStatusSummary(now);
					pm.maybeUpdateOldestNeverConnectedDarknetPeerAge(now);
					stats.maybeUpdatePeerManagerUserAlertStats(now);
					stats.maybeUpdateNodeIOStats(now);
					pm.maybeUpdatePeerNodeRoutableConnectionStats(now);

					if (logMINOR)
						Logger.minor(PacketSender.class,
								"Finished running shedulePeriodicJob() at "
										+ System.currentTimeMillis());
				} finally {
					node.ticker.queueTimedJob(this, 1000);
				}
			}
		}, 1000);
	}

	@Override
	public void run() {
		if(logMINOR) Logger.minor(this, "In PacketSender.run()");
		freenet.support.Logger.OSThread.logPID(this);

                schedulePeriodicJob();
		/*
		 * Index of the point in the nodes list at which we sent a packet and then
		 * ran out of bandwidth. We start the loop from here next time.
		 */
		while(true) {
			lastReceivedPacketFromAnyNode = lastReportedNoPackets;
			try {
				realRun();
			} catch(OutOfMemoryError e) {
				OOMHandler.handleOOM(e);
				System.err.println("Will retry above failed operation...");
			} catch(Throwable t) {
				Logger.error(this, "Caught in PacketSender: " + t, t);
				System.err.println("Caught in PacketSender: " + t);
				t.printStackTrace();
			}
		}
	}

	/**
	 * Send loop. Strategy:
	 * - Each peer can tell us when its data needs to be sent by. This is usually 100ms after it
	 * is posted. It could vary by message type. Acknowledgements also become valid 100ms after 
	 * being queued.
	 * - If any peer's data is overdue, send the data from the most overdue peer.
	 * - If there are peers with more than a packet's worth of data queued, send the data from the
	 * peer with the oldest data.
	 * - If there are peers with overdue ack's, send to the peer whose acks are oldest.
	 * 
	 * It does not attempt to ensure fairness, it attempts to minimise latency. Fairness is best
	 * dealt with at a higher level e.g. requests, although some transfers are not part of requests,
	 * e.g. bulk f2f transfers, so we may need to reconsider this eventually...
	 */
	private void realRun() {
		long now = System.currentTimeMillis();
                PeerManager pm;
		PeerNode[] nodes;

        pm = node.peers;
        nodes = pm.myPeers();

		long nextActionTime = Long.MAX_VALUE;
		long oldTempNow = now;

		boolean canSendThrottled = false;

		int MAX_PACKET_SIZE = node.getDarknetMaxPacketSize();
		long count = node.outputThrottle.getCount();
		if(count > MAX_PACKET_SIZE)
			canSendThrottled = true;
		else {
			long canSendAt = node.outputThrottle.getNanosPerTick() * (MAX_PACKET_SIZE - count);
			canSendAt = (canSendAt + 1000*1000 - 1) / (1000*1000);
			if(logMINOR)
				Logger.minor(this, "Can send throttled packets in "+canSendAt+"ms");
			nextActionTime = Math.min(nextActionTime, now + canSendAt);
		}
		
		/** The earliest time at which a peer needs to send a packet, which is before
		 * now. Throttled if canSendThrottled, otherwise not throttled. */
		long lowestUrgentSendTime = Long.MAX_VALUE;
		/** The peer(s) which lowestUrgentSendTime is referring to */
		ArrayList<PeerNode> urgentSendPeers = null;
		/** The earliest time at which a peer needs to send a packet, which is after
		 * now, where there is a full packet's worth of data to send. 
		 * Throttled if canSendThrottled, otherwise not throttled. */
		long lowestFullPacketSendTime = Long.MAX_VALUE;
		/** The peer(s) which lowestFullPacketSendTime is referring to */
		ArrayList<PeerNode> urgentFullPacketPeers = null;
		/** The earliest time at which a peer needs to send an ack, before now. */
		long lowestAckTime = Long.MAX_VALUE;
		/** The peer(s) which lowestAckTime is referring to */
		ArrayList<PeerNode> ackPeers = null;
		/** The earliest time at which a peer needs to handshake. */
		long lowestHandshakeTime = Long.MAX_VALUE;
		/** The peer(s) which lowestHandshakeTime is referring to */
		ArrayList<PeerNode> handshakePeers = null;

		for(PeerNode pn: nodes) {
			now = System.currentTimeMillis();
			
			// Basic peer maintenance.
			
			// For purposes of detecting not having received anything, which indicates a 
			// serious connectivity problem, we want to look for *any* packets received, 
			// including auth packets.
			lastReceivedPacketFromAnyNode =
				Math.max(pn.lastReceivedPacketTime(), lastReceivedPacketFromAnyNode);
			pn.maybeOnConnect();
			if(pn.shouldDisconnectAndRemoveNow() && !pn.isDisconnecting()) {
				// Might as well do it properly.
				node.peers.disconnectAndRemove(pn, true, true, false);
			}

			if(pn.isConnected()) {
				
				boolean shouldThrottle = pn.shouldThrottle();
				
				pn.checkForLostPackets();

				// Is the node dead?
				// It might be disconnected in terms of FNP but trying to reconnect via JFK's, so we need to use the time when we last got a *data* packet.
				if(now - pn.lastReceivedDataPacketTime() > pn.maxTimeBetweenReceivedPackets()) {
					Logger.normal(this, "Disconnecting from " + pn + " - haven't received packets recently");
					// Hopefully this is a transient network glitch, but stuff will have already started to timeout, so lets dump the pending messages.
					pn.disconnected(true, false);
					continue;
				} else if(now - pn.lastReceivedAckTime() > pn.maxTimeBetweenReceivedAcks() && !pn.isDisconnecting()) {
					// FIXME better to disconnect immediately??? Or check canSend()???
					Logger.normal(this, "Disconnecting from " + pn + " - haven't received acks recently");
					// Do it properly.
					// There appears to be connectivity from them to us but not from us to them.
					// So it is helpful for them to know that we are disconnecting.
					node.peers.disconnect(pn, true, true, false, true, false, 5*1000);
					continue;
				} else if(pn.isRoutable() && pn.noLongerRoutable()) {
					/*
					 NOTE: Whereas isRoutable() && noLongerRoutable() are generally mutually exclusive, this
					 code will only execute because of the scheduled-runnable in start() which executes
					 updateVersionRoutablity() on all our peers. We don't disconnect the peer, but mark it
					 as being incompatible.
					 */
					pn.invalidate(now);
					Logger.normal(this, "shouldDisconnectNow has returned true : marking the peer as incompatible: "+pn);
					continue;
				}

				// The peer is connected.
				
				if(canSendThrottled || !shouldThrottle) {
					// We can send to this peer.
					long sendTime = pn.getNextUrgentTime(now);
					if(sendTime != Long.MAX_VALUE) {
						if(sendTime <= now) {
							// Message is urgent.
							if(sendTime < lowestUrgentSendTime) {
								lowestUrgentSendTime = sendTime;
								if(urgentSendPeers != null)
									urgentSendPeers.clear();
								else
									urgentSendPeers = new ArrayList<PeerNode>();
							}
							if(sendTime <= lowestUrgentSendTime)
								urgentSendPeers.add(pn);
						} else if(pn.fullPacketQueued()) {
							if(sendTime < lowestFullPacketSendTime) {
								lowestFullPacketSendTime = sendTime;
								if(urgentFullPacketPeers != null)
									urgentFullPacketPeers.clear();
								else
									urgentFullPacketPeers = new ArrayList<PeerNode>();
							}
							if(sendTime <= lowestFullPacketSendTime)
								urgentFullPacketPeers.add(pn);
						}
					}
				} else if(shouldThrottle && !canSendThrottled) {
					long ackTime = pn.timeSendAcks();
					if(ackTime != Long.MAX_VALUE) {
						if(ackTime <= now) {
							if(ackTime < lowestAckTime) {
								lowestAckTime = ackTime;
								if(ackPeers != null)
									ackPeers.clear();
								else
									ackPeers = new ArrayList<PeerNode>();
							}
							if(ackTime <= lowestAckTime)
								ackPeers.add(pn);
						}
					}
				}
				
				if(canSendThrottled || !shouldThrottle) {
					long urgentTime = pn.getNextUrgentTime(now);
					// Should spam the logs, unless there is a deadlock
					if(urgentTime < Long.MAX_VALUE && logMINOR)
						Logger.minor(this, "Next urgent time: " + urgentTime + "(in "+(urgentTime - now)+") for " + pn);
					nextActionTime = Math.min(nextActionTime, urgentTime);
				} else {
					nextActionTime = Math.min(nextActionTime, pn.timeCheckForLostPackets());
				}
			} else
				// Not connected

				if(pn.noContactDetails())
					pn.startARKFetcher();

			long handshakeTime = pn.timeSendHandshake(now);
			if(handshakeTime != Long.MAX_VALUE) {
				if(handshakeTime < lowestHandshakeTime) {
					lowestHandshakeTime = handshakeTime;
					if(handshakePeers != null)
						handshakePeers.clear();
					else
						handshakePeers = new ArrayList<PeerNode>();
				}
				if(handshakeTime <= lowestHandshakeTime)
					handshakePeers.add(pn);
			}
			
			long tempNow = System.currentTimeMillis();
			if((tempNow - oldTempNow) > (5 * 1000))
				Logger.error(this, "tempNow is more than 5 seconds past oldTempNow (" + (tempNow - oldTempNow) + ") in PacketSender working with " + pn.userToString());
			oldTempNow = tempNow;
		}
		
		// We may send a packet, send an ack-only packet, or send a handshake.
		
		PeerNode toSendPacket = null;
		PeerNode toSendAckOnly = null;
		PeerNode toSendHandshake = null;
		
		long t = Long.MAX_VALUE;
		
		if(lowestUrgentSendTime <= now) {
			// We need to send a full packet.
			toSendPacket = urgentSendPeers.get(localRandom.nextInt(urgentSendPeers.size()));
			t = lowestUrgentSendTime;
		} else if(lowestFullPacketSendTime < Long.MAX_VALUE) {
			toSendPacket = urgentFullPacketPeers.get(localRandom.nextInt(urgentFullPacketPeers.size()));
			t = lowestFullPacketSendTime;
		} else if(lowestAckTime <= now) {
			// We need to send an ack
			toSendAckOnly = ackPeers.get(localRandom.nextInt(ackPeers.size()));
			t = lowestAckTime;
		}
		
		if(lowestHandshakeTime <= now && t > lowestHandshakeTime) {
			toSendHandshake = handshakePeers.get(localRandom.nextInt(handshakePeers.size()));
			toSendPacket = null;
			toSendAckOnly = null;
		}
		
		if(toSendPacket != null) {
			try {
				if(toSendPacket.maybeSendPacket(now, false)) {
					count = node.outputThrottle.getCount();
					if(count > MAX_PACKET_SIZE)
						canSendThrottled = true;
					else {
						canSendThrottled = false;
						long canSendAt = node.outputThrottle.getNanosPerTick() * (MAX_PACKET_SIZE - count);
						canSendAt = (canSendAt + 1000*1000 - 1) / (1000*1000);
						if(logMINOR)
							Logger.minor(this, "Can send throttled packets in "+canSendAt+"ms");
						nextActionTime = Math.min(nextActionTime, now + canSendAt);
					}
				}
			} catch (BlockedTooLongException e) {
				Logger.error(this, "Waited too long: "+TimeUtil.formatTime(e.delta)+" to allocate a packet number to send to "+toSendPacket+" : "+("(new packet format)")+" (version "+toSendPacket.getVersionNumber()+") - DISCONNECTING!");
				toSendPacket.forceDisconnect();
			}

			if(canSendThrottled || !toSendPacket.shouldThrottle()) {
				long urgentTime = toSendPacket.getNextUrgentTime(now);
				// Should spam the logs, unless there is a deadlock
				if(urgentTime < Long.MAX_VALUE && logMINOR)
					Logger.minor(this, "Next urgent time: " + urgentTime + "(in "+(urgentTime - now)+") for " + toSendPacket);
				nextActionTime = Math.min(nextActionTime, urgentTime);
			} else {
				nextActionTime = Math.min(nextActionTime, toSendPacket.timeCheckForLostPackets());
			}

		} else if(toSendAckOnly != null) {
			try {
				if(toSendAckOnly.maybeSendPacket(now, true)) {
					count = node.outputThrottle.getCount();
					if(count > MAX_PACKET_SIZE)
						canSendThrottled = true;
					else {
						canSendThrottled = false;
						long canSendAt = node.outputThrottle.getNanosPerTick() * (MAX_PACKET_SIZE - count);
						canSendAt = (canSendAt + 1000*1000 - 1) / (1000*1000);
						if(logMINOR)
							Logger.minor(this, "Can send throttled packets in "+canSendAt+"ms");
						nextActionTime = Math.min(nextActionTime, now + canSendAt);
					}
				}
			} catch (BlockedTooLongException e) {
				Logger.error(this, "Waited too long: "+TimeUtil.formatTime(e.delta)+" to allocate a packet number to send to "+toSendAckOnly+" : "+("(new packet format)")+" (version "+toSendAckOnly.getVersionNumber()+") - DISCONNECTING!");
				toSendAckOnly.forceDisconnect();
			}

			if(canSendThrottled || !toSendAckOnly.shouldThrottle()) {
				long urgentTime = toSendAckOnly.getNextUrgentTime(now);
				// Should spam the logs, unless there is a deadlock
				if(urgentTime < Long.MAX_VALUE && logMINOR)
					Logger.minor(this, "Next urgent time: " + urgentTime + "(in "+(urgentTime - now)+") for " + toSendAckOnly);
				nextActionTime = Math.min(nextActionTime, urgentTime);
			} else {
				nextActionTime = Math.min(nextActionTime, toSendAckOnly.timeCheckForLostPackets());
			}
		}
		
		if(toSendHandshake != null) {
			// Send handshake if necessary
			long beforeHandshakeTime = System.currentTimeMillis();
			toSendHandshake.getOutgoingMangler().sendHandshake(toSendHandshake, false);
			long afterHandshakeTime = System.currentTimeMillis();
			if((afterHandshakeTime - beforeHandshakeTime) > (2 * 1000))
				Logger.error(this, "afterHandshakeTime is more than 2 seconds past beforeHandshakeTime (" + (afterHandshakeTime - beforeHandshakeTime) + ") in PacketSender working with " + toSendHandshake.userToString());
		}
		
		// All of these take into account whether the data can be sent already.
		// So we can include them in nextActionTime.
		nextActionTime = Math.min(nextActionTime, lowestUrgentSendTime);
		nextActionTime = Math.min(nextActionTime, lowestFullPacketSendTime);
		nextActionTime = Math.min(nextActionTime, lowestAckTime);
		nextActionTime = Math.min(nextActionTime, lowestHandshakeTime);

		// FIXME: If we send something we will have to go around the loop again.
		// OPTIMISATION: We could track the second best, and check how many are in the array.
		
		/* Attempt to connect to old-opennet-peers.
		 * Constantly send handshake packets, in order to get through a NAT.
		 * Most JFK(1)'s are less than 300 bytes. 25*300/15 = avg 500B/sec bandwidth cost.
		 * Well worth it to allow us to reconnect more quickly. */

		OpennetManager om = node.getOpennet();
		if(om != null && node.getUptime() > 30*1000) {
			PeerNode[] peers = om.getOldPeers();

			for(PeerNode pn : peers) {
				long lastConnected = pn.timeLastConnected(now);
				if(lastConnected <= 0)
					Logger.error(this, "Last connected is zero or negative for old-opennet-peer "+pn);
				// Will be removed by next line.
				if(now - lastConnected > OpennetManager.MAX_TIME_ON_OLD_OPENNET_PEERS) {
					om.purgeOldOpennetPeer(pn);
					if(logMINOR) Logger.minor(this, "Removing old opennet peer (too old): "+pn+" age is "+TimeUtil.formatTime(now - lastConnected));
					continue;
				}
				if(pn.isConnected()) continue; // Race condition??
				if(pn.noContactDetails()) {
					pn.startARKFetcher();
					continue;
				}
				if(pn.shouldSendHandshake()) {
					// Send handshake if necessary
					long beforeHandshakeTime = System.currentTimeMillis();
					pn.getOutgoingMangler().sendHandshake(pn, true);
					long afterHandshakeTime = System.currentTimeMillis();
					if((afterHandshakeTime - beforeHandshakeTime) > (2 * 1000))
						Logger.error(this, "afterHandshakeTime is more than 2 seconds past beforeHandshakeTime (" + (afterHandshakeTime - beforeHandshakeTime) + ") in PacketSender working with " + pn.userToString());
				}
			}

		}

		long oldNow = now;

		// Send may have taken some time
		now = System.currentTimeMillis();

		if((now - oldNow) > (10 * 1000))
			Logger.error(this, "now is more than 10 seconds past oldNow (" + (now - oldNow) + ") in PacketSender");

		long sleepTime = nextActionTime - now;
		
		// MAX_COALESCING_DELAYms maximum sleep time - same as the maximum coalescing delay
		sleepTime = Math.min(sleepTime, MAX_COALESCING_DELAY);

		if(now - node.startupTime > 60 * 1000 * 5)
			if(now - lastReceivedPacketFromAnyNode > Node.ALARM_TIME) {
				Logger.error(this, "Have not received any packets from any node in last " + Node.ALARM_TIME / 1000 + " seconds");
				lastReportedNoPackets = now;
			}

		if(sleepTime > 0) {
			// Update logging only when have time to do so
			try {
				if(logMINOR)
					Logger.minor(this, "Sleeping for " + sleepTime);
				synchronized(this) {
					wait(sleepTime);
				}
			} catch(InterruptedException e) {
			// Ignore, just wake up. Probably we got interrupt()ed
			// because a new packet came in.
			}
		} else {
			if(logDEBUG)
				Logger.debug(this, "Next urgent time is "+(now - nextActionTime)+"ms in the past");
		}
	}

	/** Wake up, and send any queued packets. */
	public void wakeUp() {
		// Wake up if needed
		synchronized(this) {
			notifyAll();
		}
	}

	protected String l10n(String key, String[] patterns, String[] values) {
		return NodeL10n.getBase().getString("PacketSender."+key, patterns, values);
	}

	protected String l10n(String key, String pattern, String value) {
		return NodeL10n.getBase().getString("PacketSender."+key, pattern, value);
	}
}