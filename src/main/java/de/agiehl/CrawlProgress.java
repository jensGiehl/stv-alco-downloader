package de.agiehl;

import java.nio.file.Path;

final class CrawlProgress {

    private int attempts;
    private int sessionRestarts;
    private int contractsDiscovered;
    private int contractsCompleted;
    private int pages;
    private int documentsDiscovered;
    private int documentsDownloaded;
    private long attachmentBytes;
    private int warnings;
    private Path snapshot;

    void startAttempt() {
        attempts++;
        contractsDiscovered = 0;
        contractsCompleted = 0;
        pages = 0;
        documentsDiscovered = 0;
        documentsDownloaded = 0;
        attachmentBytes = 0;
        warnings = 0;
        snapshot = null;
    }

    void sessionRestarted() {
        sessionRestarts++;
    }

    void snapshotCreated(Path path) {
        snapshot = path;
    }

    void contractsDiscovered(int count) {
        contractsDiscovered = count;
    }

    void contractCompleted() {
        contractsCompleted++;
    }

    void pages(int count) {
        pages = count;
    }

    void documentsDiscovered(int count) {
        documentsDiscovered = count;
    }

    void documentsDownloaded(int count, long bytes) {
        documentsDownloaded = count;
        attachmentBytes = bytes;
    }

    void warnings(int count) {
        warnings = count;
    }

    int attempts() {
        return attempts;
    }

    int sessionRestarts() {
        return sessionRestarts;
    }

    int contractsDiscovered() {
        return contractsDiscovered;
    }

    int contractsCompleted() {
        return contractsCompleted;
    }

    int pages() {
        return pages;
    }

    int documentsDiscovered() {
        return documentsDiscovered;
    }

    int documentsDownloaded() {
        return documentsDownloaded;
    }

    long attachmentBytes() {
        return attachmentBytes;
    }

    int warnings() {
        return warnings;
    }

    String snapshot() {
        return snapshot == null ? "-" : snapshot.toString();
    }
}
