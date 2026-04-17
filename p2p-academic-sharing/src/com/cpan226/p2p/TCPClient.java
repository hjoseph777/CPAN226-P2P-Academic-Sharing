package com.cpan226.p2p;

import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.util.*;

/**
 * TCPClient — Downloads files from a remote peer's TCPServer.
 *
 * Supports:
 *   listRemoteFiles(host, port)           → fetches LIST from remote server
 *   downloadFile(host, port, filename)    → downloads file with retry + integrity check
 *
 * Wire protocol (GET):
 *   Client → Server:  "GET <filename>\n"
 *   Server → Client:  "SIZE <bytes>\n"
 *                     "CHECKSUM <sha256hex>\n"
 *                     [binary data — exactly <bytes> bytes]
 *
 * Key design: header lines are read byte-by-byte (via TCPServer.readLine)
 * so the same InputStream can safely switch to bulk binary reads afterward.
 */
public class TCPClient {

    private static final int SOCKET_TIMEOUT_MS = 30_000; // 30 s

    private final FileManager      fileManager;
    private final IntegrityChecker integrityChecker;

    public TCPClient(FileManager fileManager, IntegrityChecker integrityChecker) {
        this.fileManager      = fileManager;
        this.integrityChecker = integrityChecker;
    }

    // ─────────────────────────────────────────────────────────────────
    // List remote files
    // ─────────────────────────────────────────────────────────────────

    /**
     * Sends a LIST command to a remote peer and returns the list of filenames.
     *
     * @throws IOException if the connection fails
     */
    public List<String> listRemoteFiles(String host, int port) throws IOException {
        List<String> files = new ArrayList<>();

        try (Socket socket = openSocket(host, port)) {
            // LIST uses pure text — safe to use BufferedReader here
            PrintWriter  out = new PrintWriter(new OutputStreamWriter(socket.getOutputStream(), "UTF-8"), true);
            BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream(), "UTF-8"));

            out.println("LIST");

            String line;
            while ((line = in.readLine()) != null && !line.equals("END")) {
                files.add(line);
            }
        }

        return files;
    }

    // ─────────────────────────────────────────────────────────────────
    // Download with retry
    // ─────────────────────────────────────────────────────────────────

    /**
     * Downloads a file from a remote peer.
     * Retries up to IntegrityChecker.MAX_RETRIES times on failure.
     *
     * @return true if the file was downloaded and verified successfully
     */
    public boolean downloadFile(String host, int port, String filename) {
        int maxAttempts = integrityChecker.getMaxRetries();

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            System.out.printf("[TCP Client] 📥 Attempt %d/%d — %s from %s:%d%n",
                    attempt, maxAttempts, filename, host, port);
            try {
                boolean ok = attemptDownload(host, port, filename);
                if (ok) {
                    System.out.println("[TCP Client] ✅ Download complete: " + filename);
                    return true;
                }
                System.out.println("[TCP Client] ⚠ Integrity check failed — will retry");
            } catch (Exception e) {
                System.err.println("[TCP Client] ❌ Attempt " + attempt + " error: " + e.getMessage());
            }
        }

        System.err.println("[TCP Client] ❌ Giving up after " + maxAttempts + " attempts: " + filename);
        return false;
    }

    // ─────────────────────────────────────────────────────────────────
    // Single download attempt
    // ─────────────────────────────────────────────────────────────────

    private boolean attemptDownload(String host, int port, String filename) throws IOException {
        try (Socket socket = openSocket(host, port)) {
            OutputStream  rawOut = socket.getOutputStream();
            InputStream   rawIn  = socket.getInputStream();

            // ── Send GET request ──
            String request = "GET " + filename + "\n";
            rawOut.write(request.getBytes("UTF-8"));
            rawOut.flush();

            // ── Read header lines byte-by-byte (safe for binary switch) ──
            String sizeLine     = TCPServer.readLine(rawIn);
            String checksumLine = TCPServer.readLine(rawIn);

            if (sizeLine == null || sizeLine.startsWith("ERROR")) {
                System.err.println("[TCP Client] Server error: " + sizeLine);
                return false;
            }

            long   expectedSize     = Long.parseLong(sizeLine.replace("SIZE", "").trim());
            String expectedChecksum = checksumLine.replace("CHECKSUM", "").trim();

            System.out.printf("[TCP Client] Receiving %-30s  expected %d bytes%n", filename, expectedSize);

            // ── Receive binary payload ──
            Path downloadPath = fileManager.getDownloadPath(filename);
            long received     = 0;

            try (FileOutputStream fos = new FileOutputStream(downloadPath.toFile())) {
                byte[] buffer = new byte[FileManager.CHUNK_SIZE];
                int bytesRead;

                while (received < expectedSize) {
                    int toRead = (int) Math.min(buffer.length, expectedSize - received);
                    bytesRead  = rawIn.read(buffer, 0, toRead);
                    if (bytesRead == -1) break;  // Connection dropped early
                    fos.write(buffer, 0, bytesRead);
                    received += bytesRead;
                }
            }

            System.out.printf("[TCP Client] Received %d / %d bytes%n", received, expectedSize);

            // ── Size sanity check ──
            if (received != expectedSize) {
                System.err.println("[TCP Client] Size mismatch — expected " + expectedSize + ", got " + received);
                Files.deleteIfExists(downloadPath);  // Clean up partial file
                return false;
            }

            // ── Integrity check ──
            return integrityChecker.verify(downloadPath.toFile(), expectedChecksum);
        }
    }

    // ─────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────

    private Socket openSocket(String host, int port) throws IOException {
        Socket socket = new Socket();
        socket.connect(new InetSocketAddress(host, port), SOCKET_TIMEOUT_MS);
        socket.setSoTimeout(SOCKET_TIMEOUT_MS);
        return socket;
    }
}
