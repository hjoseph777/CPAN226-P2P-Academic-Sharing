package com.cpan226.p2p;

import java.net.*;
import java.util.*;
import java.util.concurrent.*;

/**
 * UDPDiscovery — Peer-to-peer discovery over UDP broadcast.
 *
 * How it works:
 *  1. Every BROADCAST_INTERVAL_MS, this node broadcasts a PEER_ANNOUNCE message
 *     to the LAN broadcast address (255.255.255.255:9000).
 *  2. The listener thread receives announcements from other peers and adds
 *     them to the peer registry.
 *  3. A cleanup thread removes peers that haven't been heard from in 30 seconds.
 *
 * Message format:
 *   PEER_ANNOUNCE|<hostname>|<tcpPort>|<fileCount>
 */
public class UDPDiscovery implements Runnable {

    public  static final int    UDP_PORT             = 9000;
    private static final int    BROADCAST_INTERVAL_MS = 10_000;  // 10 s
    private static final int    PEER_TIMEOUT_MS       = 30_000;  // 30 s
    private static final String ANNOUNCE_PREFIX       = "PEER_ANNOUNCE";

    private final String      localHostname;
    private final int         localTcpPort;
    private final FileManager fileManager;

    /** Registry: "host:port" → PeerInfo */
    private final ConcurrentHashMap<String, PeerInfo> peerRegistry = new ConcurrentHashMap<>();

    private volatile boolean running = true;

    // ─────────────────────────────────────────────────────────────────
    // PeerInfo record
    // ─────────────────────────────────────────────────────────────────

    public static class PeerInfo {
        public final String host;
        public final int    tcpPort;
        public final int    fileCount;
        public final long   lastSeen;

        public PeerInfo(String host, int tcpPort, int fileCount) {
            this.host      = host;
            this.tcpPort   = tcpPort;
            this.fileCount = fileCount;
            this.lastSeen  = System.currentTimeMillis();
        }

        public String key() { return host + ":" + tcpPort; }

        @Override
        public String toString() {
            return String.format("%-20s (TCP :%d | %d files)", host, tcpPort, fileCount);
        }
    }

    // ─────────────────────────────────────────────────────────────────
    // Constructor
    // ─────────────────────────────────────────────────────────────────

    public UDPDiscovery(String localHostname, int localTcpPort, FileManager fileManager) {
        this.localHostname = localHostname;
        this.localTcpPort  = localTcpPort;
        this.fileManager   = fileManager;
    }

    // ─────────────────────────────────────────────────────────────────
    // Main run — spawns three daemon threads
    // ─────────────────────────────────────────────────────────────────

    @Override
    public void run() {
        startDaemon("UDP-Broadcaster", this::broadcastLoop);
        startDaemon("UDP-Listener",    this::listenLoop);
        startDaemon("UDP-Cleanup",     this::cleanupLoop);

        while (running) {
            try { Thread.sleep(1000); } catch (InterruptedException e) { break; }
        }
    }

    private void startDaemon(String name, Runnable task) {
        Thread t = new Thread(task, name);
        t.setDaemon(true);
        t.start();
    }

    // ─────────────────────────────────────────────────────────────────
    // Broadcast loop — announces this node every 10 seconds
    // ─────────────────────────────────────────────────────────────────

    private void broadcastLoop() {
        try (DatagramSocket socket = new DatagramSocket()) {
            socket.setBroadcast(true);
            InetAddress broadcastAddr = InetAddress.getByName("255.255.255.255");

            System.out.println("[UDP] Broadcaster started — announcing every "
                    + (BROADCAST_INTERVAL_MS / 1000) + "s on port " + UDP_PORT);

            while (running) {
                String  message = buildAnnouncement();
                byte[]  data    = message.getBytes();
                DatagramPacket packet = new DatagramPacket(data, data.length, broadcastAddr, UDP_PORT);
                socket.send(packet);
                System.out.println("[UDP] 📢 Broadcast: " + message);
                Thread.sleep(BROADCAST_INTERVAL_MS);
            }
        } catch (Exception e) {
            System.err.println("[UDP] Broadcast error: " + e.getMessage());
        }
    }

    // ─────────────────────────────────────────────────────────────────
    // Listen loop — receives announcements from other peers
    // ─────────────────────────────────────────────────────────────────

    private void listenLoop() {
        try (DatagramSocket socket = new DatagramSocket(UDP_PORT)) {
            socket.setBroadcast(true);
            byte[] buffer = new byte[512];
            System.out.println("[UDP] Listener ready on port " + UDP_PORT);

            while (running) {
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                socket.receive(packet);

                String senderIP = packet.getAddress().getHostAddress();
                String message  = new String(packet.getData(), 0, packet.getLength()).trim();
                handleAnnouncement(message, senderIP);
            }
        } catch (Exception e) {
            if (running) System.err.println("[UDP] Listener error: " + e.getMessage());
        }
    }

    // ─────────────────────────────────────────────────────────────────
    // Cleanup loop — removes peers not heard from in 30 seconds
    // ─────────────────────────────────────────────────────────────────

    private void cleanupLoop() {
        while (running) {
            try {
                Thread.sleep(5_000);
                long now = System.currentTimeMillis();
                peerRegistry.entrySet().removeIf(e -> {
                    boolean stale = (now - e.getValue().lastSeen) > PEER_TIMEOUT_MS;
                    if (stale) System.out.println("[UDP] 🗑️  Removed stale peer: " + e.getKey());
                    return stale;
                });
            } catch (InterruptedException e) {
                break;
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────

    private String buildAnnouncement() {
        int fileCount = fileManager.listSharedFiles().size();
        return ANNOUNCE_PREFIX + "|" + localHostname + "|" + localTcpPort + "|" + fileCount;
    }

    private void handleAnnouncement(String message, String senderIP) {
        if (!message.startsWith(ANNOUNCE_PREFIX)) return;

        String[] parts = message.split("\\|");
        if (parts.length < 4) return;

        try {
            String host      = parts[1];
            int    port      = Integer.parseInt(parts[2].trim());
            int    fileCount = Integer.parseInt(parts[3].trim());

            // Use the actual sender IP if the announced hostname is ambiguous
            if (host.equals("localhost") || host.equals("127.0.0.1")) {
                host = senderIP;
            }

            // Ignore our own broadcasts
            if (port == localTcpPort && isSelf(host, senderIP)) return;

            PeerInfo info   = new PeerInfo(host, port, fileCount);
            boolean  isNew  = !peerRegistry.containsKey(info.key());
            peerRegistry.put(info.key(), info);

            if (isNew) {
                System.out.println("[UDP] 🆕 Discovered: " + info);
            }
        } catch (NumberFormatException e) {
            System.err.println("[UDP] Malformed announcement: " + message);
        }
    }

    /** Returns true if the announced host/port matches this node. */
    private boolean isSelf(String host, String senderIP) {
        return host.equals(localHostname)
            || host.equals("127.0.0.1")
            || host.equals(senderIP);
    }

    // ─────────────────────────────────────────────────────────────────
    // Public API
    // ─────────────────────────────────────────────────────────────────

    /** Returns a snapshot of currently known peers. */
    public List<PeerInfo> getPeers() {
        return new ArrayList<>(peerRegistry.values());
    }

    public void stop() { running = false; }
}
