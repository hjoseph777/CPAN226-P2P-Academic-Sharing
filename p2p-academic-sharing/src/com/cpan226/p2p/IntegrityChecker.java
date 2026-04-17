package com.cpan226.p2p;

import java.io.*;

/**
 * IntegrityChecker — Validates the integrity of received files.
 *
 * Responsibilities:
 *  - Compare actual SHA-256 of a downloaded file against the expected checksum
 *  - Report pass / fail with detailed output
 *  - Expose MAX_RETRIES so TCPClient knows when to give up
 */
public class IntegrityChecker {

    /** Maximum number of download retries before giving up. */
    public static final int MAX_RETRIES = 3;

    private final FileManager fileManager;

    public IntegrityChecker(FileManager fileManager) {
        this.fileManager = fileManager;
    }

    // ─────────────────────────────────────────────────────────────────
    // Verification
    // ─────────────────────────────────────────────────────────────────

    /**
     * Verifies a downloaded file against the expected SHA-256 checksum.
     *
     * @param file             the downloaded file on disk
     * @param expectedChecksum hex string of expected SHA-256 digest
     * @return true if the checksum matches, false otherwise
     */
    public boolean verify(File file, String expectedChecksum) {
        if (!file.exists()) {
            System.err.println("[Integrity] File not found: " + file.getName());
            return false;
        }

        try {
            String actual = fileManager.computeChecksum(file);
            boolean match = actual.equalsIgnoreCase(expectedChecksum.trim());

            if (match) {
                System.out.println("[Integrity] ✅  PASS  " + file.getName());
                System.out.println("            SHA-256 : " + actual);
            } else {
                System.out.println("[Integrity] ❌  FAIL  " + file.getName());
                System.out.println("            Expected : " + expectedChecksum);
                System.out.println("            Got      : " + actual);
            }
            return match;

        } catch (Exception e) {
            System.err.println("[Integrity] Error computing checksum: " + e.getMessage());
            return false;
        }
    }

    public int getMaxRetries() {
        return MAX_RETRIES;
    }
}
