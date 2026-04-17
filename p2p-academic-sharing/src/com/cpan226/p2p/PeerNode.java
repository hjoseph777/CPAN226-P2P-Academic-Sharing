package com.cpan226.p2p;

import java.io.*;
import java.net.*;
import java.util.*;

/**
 * PeerNode — Main entry point for the P2P Academic Resource Sharing Network.
 *
 * Wires all components together:
 *   FileManager      → local file index + SHA-256
 *   IntegrityChecker → verify downloads
 *   UDPDiscovery     → peer broadcast/listen/cleanup
 *   TCPServer        → serve files to other peers
 *   TCPClient        → download files from other peers
 *
 * Usage:
 *   java com.cpan226.p2p.PeerNode --port <tcp_port> --shared <shared_dir>
 *
 * Defaults:
 *   --port   6000
 *   --shared ./shared
 *
 * Demo (two terminals on one machine):
 *   Terminal 1:  java ... PeerNode --port 6000 --shared ./shared-a
 *   Terminal 2:  java ... PeerNode --port 6001 --shared ./shared-b
 */
public class PeerNode {

    // ─────────────────────────────────────────────────────────────────
    // Defaults
    // ─────────────────────────────────────────────────────────────────
    private static final int    DEFAULT_TCP_PORT   = 6000;
    private static final String DEFAULT_SHARED_DIR = "./shared";
    private static final String DOWNLOADS_DIR      = "./downloads";

    // ─────────────────────────────────────────────────────────────────
    // Components
    // ─────────────────────────────────────────────────────────────────
    private final FileManager      fileManager;
    private final IntegrityChecker integrityChecker;
    private final UDPDiscovery     udpDiscovery;
    private final TCPServer        tcpServer;
    private final TCPClient        tcpClient;

    private final int    tcpPort;
    private final String hostname;

    // ─────────────────────────────────────────────────────────────────
    // Startup
    // ─────────────────────────────────────────────────────────────────

    public PeerNode(int tcpPort, String sharedDir) throws IOException {
        this.tcpPort  = tcpPort;
        this.hostname = resolveHostname();

        fileManager      = new FileManager(sharedDir, DOWNLOADS_DIR);
        integrityChecker = new IntegrityChecker(fileManager);
        udpDiscovery     = new UDPDiscovery(hostname, tcpPort, fileManager);
        tcpServer        = new TCPServer(tcpPort, fileManager);
        tcpClient        = new TCPClient(fileManager, integrityChecker);
    }

    /** Starts all background services. */
    public void start() {
        printBanner();

        // UDP Discovery daemon
        Thread udpThread = new Thread(udpDiscovery, "UDPDiscovery");
        udpThread.setDaemon(true);
        udpThread.start();

        // TCP Server daemon
        Thread tcpThread = new Thread(tcpServer, "TCPServer");
        tcpThread.setDaemon(true);
        tcpThread.start();

        // Give threads a moment to bind
        try { Thread.sleep(500); } catch (InterruptedException ignored) {}

        System.out.println("\n[PeerNode] ✅ Node ready. Type 'help' to see commands.\n");
    }

    // ─────────────────────────────────────────────────────────────────
    // CLI
    // ─────────────────────────────────────────────────────────────────

    public void runCLI() {
        Scanner scanner = new Scanner(System.in);
        System.out.print("> ");

        while (scanner.hasNextLine()) {
            String input = scanner.nextLine().trim();
            if (!input.isEmpty()) {
                process(input);
            }
            System.out.print("> ");
        }

        shutdown();
    }

    private void process(String input) {
        String[] tokens = input.split("\\s+", 3);
        String   cmd    = tokens[0].toLowerCase();

        switch (cmd) {

            case "help":
                printHelp();
                break;

            case "list":
                // list          → list my shared files
                // list <#>      → list files on peer #
                if (tokens.length == 1) {
                    listMyFiles();
                } else {
                    listPeerFiles(tokens[1]);
                }
                break;

            case "peers":
                listPeers();
                break;

            case "get":
                // get <peer#> <filename>
                if (tokens.length < 3) {
                    System.out.println("Usage: get <peer#> <filename>");
                } else {
                    downloadFromPeer(tokens[1], tokens[2]);
                }
                break;

            case "quit":
            case "exit":
                shutdown();
                System.exit(0);
                break;

            default:
                System.out.println("Unknown command. Type 'help'.");
        }
    }

    // ── list (local) ────────────────────────────────────────────────

    private void listMyFiles() {
        List<String> files = fileManager.listSharedFiles();
        if (files.isEmpty()) {
            System.out.println("[Local] No files in shared folder: " + fileManager.getSharedDir());
        } else {
            System.out.println("[Local] My shared files (" + files.size() + "):");
            for (int i = 0; i < files.size(); i++) {
                System.out.printf("  [%d] %s%n", i + 1, files.get(i));
            }
        }
    }

    // ── peers ────────────────────────────────────────────────────────

    private void listPeers() {
        List<UDPDiscovery.PeerInfo> peers = udpDiscovery.getPeers();
        if (peers.isEmpty()) {
            System.out.println("[Peers] No peers discovered yet. Waiting for UDP broadcasts...");
        } else {
            System.out.println("[Peers] Known peers (" + peers.size() + "):");
            for (int i = 0; i < peers.size(); i++) {
                System.out.printf("  [%d] %s%n", i + 1, peers.get(i));
            }
        }
    }

    // ── list <peer#> ─────────────────────────────────────────────────

    private void listPeerFiles(String peerIndexStr) {
        UDPDiscovery.PeerInfo peer = resolvePeer(peerIndexStr);
        if (peer == null) return;

        System.out.println("[Remote] Listing files from " + peer.host + ":" + peer.tcpPort + " ...");
        try {
            List<String> files = tcpClient.listRemoteFiles(peer.host, peer.tcpPort);
            if (files.isEmpty()) {
                System.out.println("  (no files shared)");
            } else {
                for (int i = 0; i < files.size(); i++) {
                    System.out.printf("  [%d] %s%n", i + 1, files.get(i));
                }
            }
        } catch (IOException e) {
            System.err.println("[Remote] Could not connect to peer: " + e.getMessage());
        }
    }

    // ── get <peer#> <filename> ───────────────────────────────────────

    private void downloadFromPeer(String peerIndexStr, String filename) {
        UDPDiscovery.PeerInfo peer = resolvePeer(peerIndexStr);
        if (peer == null) return;

        System.out.println("[Download] Requesting '" + filename + "' from " + peer.host + ":" + peer.tcpPort);
        boolean success = tcpClient.downloadFile(peer.host, peer.tcpPort, filename);

        if (success) {
            System.out.println("[Download] ✅ Saved to: " + fileManager.getDownloadPath(filename));
        } else {
            System.out.println("[Download] ❌ Failed to download: " + filename);
        }
    }

    // ─────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────

    /** Resolves a peer by 1-based index string. */
    private UDPDiscovery.PeerInfo resolvePeer(String indexStr) {
        List<UDPDiscovery.PeerInfo> peers = udpDiscovery.getPeers();
        if (peers.isEmpty()) {
            System.out.println("[Peers] No peers discovered yet.");
            return null;
        }
        try {
            int idx = Integer.parseInt(indexStr) - 1;
            if (idx < 0 || idx >= peers.size()) {
                System.out.println("Invalid peer number. Run 'peers' to see the list.");
                return null;
            }
            return peers.get(idx);
        } catch (NumberFormatException e) {
            System.out.println("Peer number must be an integer.");
            return null;
        }
    }

    /** Returns the local hostname or falls back to 127.0.0.1. */
    private static String resolveHostname() {
        try {
            return InetAddress.getLocalHost().getHostAddress();
        } catch (UnknownHostException e) {
            return "127.0.0.1";
        }
    }

    private void shutdown() {
        System.out.println("\n[PeerNode] Shutting down...");
        tcpServer.stop();
        udpDiscovery.stop();
        System.out.println("[PeerNode] Goodbye.");
    }

    private void printBanner() {
        System.out.println("+======================================================+");
        System.out.println("|   P2P Academic Resource Sharing Network              |");
        System.out.println("|   CPAN226 - Network Programming (2026)               |");
        System.out.println("+======================================================+");
        System.out.printf( "|  Host     : %-40s|%n", hostname);
        System.out.printf( "|  TCP Port : %-40d|%n", tcpPort);
        System.out.printf( "|  UDP Port : %-40d|%n", UDPDiscovery.UDP_PORT);
        System.out.printf( "|  Shared   : %-40s|%n", fileManager.getSharedDir().toString());
        System.out.printf( "|  Downloads: %-40s|%n", fileManager.getDownloadsDir().toString());
        System.out.println("+======================================================+");
    }

    private void printHelp() {
        System.out.println("\nAvailable commands:");
        System.out.println("  list              — list my shared files");
        System.out.println("  list <peer#>      — list files shared by a remote peer");
        System.out.println("  peers             — show all discovered peers");
        System.out.println("  get <peer#> <file>— download a file from a peer");
        System.out.println("  help              — show this help");
        System.out.println("  quit              — exit\n");
    }

    // ─────────────────────────────────────────────────────────────────
    // Main
    // ─────────────────────────────────────────────────────────────────

    public static void main(String[] args) throws IOException {
        int    port      = DEFAULT_TCP_PORT;
        String sharedDir = DEFAULT_SHARED_DIR;

        // Parse --port and --shared arguments
        for (int i = 0; i < args.length - 1; i++) {
            if (args[i].equals("--port")) {
                port = Integer.parseInt(args[i + 1]);
            } else if (args[i].equals("--shared")) {
                sharedDir = args[i + 1];
            }
        }

        PeerNode node = new PeerNode(port, sharedDir);
        node.start();
        node.runCLI();
    }
}
