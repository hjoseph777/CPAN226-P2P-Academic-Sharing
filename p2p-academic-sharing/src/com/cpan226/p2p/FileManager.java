package com.cpan226.p2p;

import java.io.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.stream.*;

/**
 * FileManager — Handles all local file operations.
 *
 * Responsibilities:
 *  - Index files in the shared directory
 *  - Compute SHA-256 checksums for integrity verification
 *  - Provide file chunks for TCP transfer (4KB buffer)
 *  - Resolve download destination paths
 */
public class FileManager {

    /** Transfer chunk size: 4KB */
    public static final int CHUNK_SIZE = 4096;

    private final Path sharedDir;
    private final Path downloadsDir;

    /**
     * @param sharedPath    path to the folder this peer will share
     * @param downloadsPath path where received files are saved
     */
    public FileManager(String sharedPath, String downloadsPath) throws IOException {
        this.sharedDir    = Paths.get(sharedPath).toAbsolutePath().normalize();
        this.downloadsDir = Paths.get(downloadsPath).toAbsolutePath().normalize();

        Files.createDirectories(sharedDir);
        Files.createDirectories(downloadsDir);

        System.out.println("[FileManager] Shared dir    : " + sharedDir);
        System.out.println("[FileManager] Downloads dir : " + downloadsDir);
    }

    // ─────────────────────────────────────────────────────────────────
    // File Listing
    // ─────────────────────────────────────────────────────────────────

    /**
     * Returns a sorted list of filenames in the shared directory.
     */
    public List<String> listSharedFiles() {
        File[] files = sharedDir.toFile().listFiles(File::isFile);
        if (files == null) return Collections.emptyList();
        return Arrays.stream(files)
                     .map(File::getName)
                     .sorted()
                     .collect(Collectors.toList());
    }

    /**
     * Retrieves a file from the shared directory by name.
     * Returns null if the file does not exist.
     */
    public File getSharedFile(String filename) {
        // Prevent path traversal attacks
        Path resolved = sharedDir.resolve(filename).normalize();
        if (!resolved.startsWith(sharedDir)) {
            System.err.println("[FileManager] Path traversal attempt blocked: " + filename);
            return null;
        }
        File f = resolved.toFile();
        return (f.exists() && f.isFile()) ? f : null;
    }

    // ─────────────────────────────────────────────────────────────────
    // Integrity / Checksum
    // ─────────────────────────────────────────────────────────────────

    /**
     * Computes the SHA-256 hex digest of the given file.
     * Reads the file in CHUNK_SIZE blocks to stay memory-efficient.
     */
    public String computeChecksum(File file) throws IOException, NoSuchAlgorithmException {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] buffer = new byte[CHUNK_SIZE];

        try (InputStream is = new BufferedInputStream(new FileInputStream(file))) {
            int bytesRead;
            while ((bytesRead = is.read(buffer)) != -1) {
                digest.update(buffer, 0, bytesRead);
            }
        }

        byte[] hashBytes = digest.digest();
        StringBuilder sb = new StringBuilder();
        for (byte b : hashBytes) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    // ─────────────────────────────────────────────────────────────────
    // Paths
    // ─────────────────────────────────────────────────────────────────

    /** Returns the destination path for a downloaded file. */
    public Path getDownloadPath(String filename) {
        return downloadsDir.resolve(filename);
    }

    public Path getSharedDir()    { return sharedDir; }
    public Path getDownloadsDir() { return downloadsDir; }
}
