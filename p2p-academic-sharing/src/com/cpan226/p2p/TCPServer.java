package com.cpan226.p2p;

import java.io.*;
import java.net.*;
import java.util.concurrent.*;

/**
 * TCPServer — Serves files to requesting peers over TCP.
 *
 * Supported commands (sent by TCPClient):
 *   LIST          → responds with newline-separated filenames, ending with "END"
 *   GET <filename> → responds with SIZE, CHECKSUM headers then raw binary data
 *
 * Wire protocol for GET:
 *   Server → Client:
 *     SIZE <bytes>\n
 *     CHECKSUM <sha256hex>\n
 *     [binary data — exactly <bytes> bytes]
 *
 * Uses an unbounded cached thread pool so multiple peers can download simultaneously.
 */
public class TCPServer implements Runnable {

    private final int         port;
    private final FileManager fileManager;
    private final ExecutorService threadPool = Executors.newCachedThreadPool();

    private volatile boolean running = true;
    private ServerSocket serverSocket;

    public TCPServer(int port, FileManager fileManager) {
        this.port        = port;
        this.fileManager = fileManager;
    }

    // ─────────────────────────────────────────────────────────────────
    // Main accept loop
    // ─────────────────────────────────────────────────────────────────

    @Override
    public void run() {
        try {
            serverSocket = new ServerSocket(port);
            System.out.println("[TCP Server] 🚀 Listening on port " + port);

            while (running) {
                try {
                    Socket client = serverSocket.accept();
                    threadPool.submit(() -> handleClient(client));
                } catch (SocketException e) {
                    if (running) System.err.println("[TCP Server] Accept error: " + e.getMessage());
                }
            }
        } catch (IOException e) {
            System.err.println("[TCP Server] Failed to start on port " + port + ": " + e.getMessage());
        }
    }

    // ─────────────────────────────────────────────────────────────────
    // Per-connection handler
    // ─────────────────────────────────────────────────────────────────

    private void handleClient(Socket client) {
        String addr = client.getInetAddress().getHostAddress() + ":" + client.getPort();
        System.out.println("[TCP Server] ← Connection from " + addr);

        try {
            // Read the first line as the command
            String command = readLine(client.getInputStream());
            if (command == null || command.isBlank()) return;

            System.out.println("[TCP Server] CMD: " + command + "  [" + addr + "]");

            if (command.equals("LIST")) {
                handleList(client.getOutputStream());
            } else if (command.startsWith("GET ")) {
                String filename = command.substring(4).trim();
                handleGet(filename, client.getOutputStream());
            } else {
                sendError(client.getOutputStream(), "Unknown command: " + command);
            }

        } catch (Exception e) {
            System.err.println("[TCP Server] Error [" + addr + "]: " + e.getMessage());
        } finally {
            try { client.close(); } catch (IOException ignored) {}
            System.out.println("[TCP Server] ✗ Closed connection: " + addr);
        }
    }

    // ─────────────────────────────────────────────────────────────────
    // LIST handler
    // ─────────────────────────────────────────────────────────────────

    private void handleList(OutputStream out) throws IOException {
        StringBuilder sb = new StringBuilder();
        for (String name : fileManager.listSharedFiles()) {
            sb.append(name).append("\n");
        }
        sb.append("END\n");
        out.write(sb.toString().getBytes("UTF-8"));
        out.flush();
        System.out.println("[TCP Server] LIST served (" + fileManager.listSharedFiles().size() + " files)");
    }

    // ─────────────────────────────────────────────────────────────────
    // GET handler — streams file in 4KB chunks
    // ─────────────────────────────────────────────────────────────────

    private void handleGet(String filename, OutputStream out) throws Exception {
        File file = fileManager.getSharedFile(filename);
        if (file == null) {
            sendError(out, "File not found: " + filename);
            return;
        }

        String checksum  = fileManager.computeChecksum(file);
        long   fileSize  = file.length();

        // ── Header ──
        String header = "SIZE " + fileSize + "\n" + "CHECKSUM " + checksum + "\n";
        out.write(header.getBytes("UTF-8"));
        out.flush();

        // ── Binary payload ──
        byte[] buffer = new byte[FileManager.CHUNK_SIZE];
        long totalSent = 0;

        try (FileInputStream fis = new FileInputStream(file)) {
            int bytesRead;
            while ((bytesRead = fis.read(buffer)) != -1) {
                out.write(buffer, 0, bytesRead);
                totalSent += bytesRead;
            }
        }
        out.flush();

        System.out.printf("[TCP Server] ✅ Served %-30s  %d bytes  checksum: %s...%n",
                filename, totalSent, checksum.substring(0, 8));
    }

    // ─────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────

    /**
     * Reads a single line byte-by-byte from a raw InputStream.
     * Safe to use before switching to binary reads on the same stream.
     */
    static String readLine(InputStream in) throws IOException {
        StringBuilder sb = new StringBuilder();
        int b;
        while ((b = in.read()) != -1) {
            if (b == '\n') break;
            if (b != '\r') sb.append((char) b);
        }
        return sb.length() == 0 ? null : sb.toString();
    }

    private void sendError(OutputStream out, String msg) throws IOException {
        String response = "ERROR " + msg + "\n";
        out.write(response.getBytes("UTF-8"));
        out.flush();
        System.err.println("[TCP Server] ⚠ " + msg);
    }

    // ─────────────────────────────────────────────────────────────────
    // Shutdown
    // ─────────────────────────────────────────────────────────────────

    public void stop() {
        running = false;
        try { if (serverSocket != null) serverSocket.close(); } catch (IOException ignored) {}
        threadPool.shutdown();
    }
}
