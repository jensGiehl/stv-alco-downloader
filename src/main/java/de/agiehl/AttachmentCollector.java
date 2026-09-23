package de.agiehl;

import java.net.URI;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class AttachmentCollector {

    private static final Logger LOGGER = LoggerFactory.getLogger(AttachmentCollector.class);

    private final Map<String, PendingDocument> documents = new LinkedHashMap<>();

    void register(SectionData section) {
        for (DiscoveredDocument document : section.documents()) {
            PendingDocument pending = documents.computeIfAbsent(document.href(), ignored -> new PendingDocument(
                    document.id(), document.title(), document.href(), section.section(),
                    section.context().getOrDefault("period", "")));
            pending.sources.add(new DocumentSource(document.sourceSection(), document.contractId(),
                    document.sourceUrl(), document.title(), document.href()));
            if (pending.title.isBlank() && !document.title().isBlank()) {
                pending.title = document.title();
            }
        }
    }

    SectionData capture(SectionData section, AlcoHttpClient client, SnapshotStore store) {
        register(section);
        downloadPending(client, store);
        Map<String, String> storedFiles = new LinkedHashMap<>();
        documents.values().stream()
                .filter(pending -> pending.storedFile != null)
                .forEach(pending -> storedFiles.put(pending.href, pending.storedFile));
        return attachStoredFiles(section, storedFiles);
    }

    List<DocumentData> downloadAll(AlcoHttpClient client, SnapshotStore store) {
        downloadPending(client, store);
        return documents();
    }

    int size() {
        return documents.size();
    }

    List<DocumentData> documents() {
        return documents.values().stream()
                .filter(pending -> pending.storedFile != null)
                .map(pending -> new DocumentData(pending.id, pending.title, pending.href, pending.contentType,
                        pending.size, pending.sha256, pending.storedFile, List.copyOf(pending.sources)))
                .toList();
    }

    private void downloadPending(AlcoHttpClient client, SnapshotStore store) {
        int index = 0;
        for (PendingDocument pending : documents.values()) {
            index++;
            if (pending.downloadAttempted) {
                continue;
            }
            pending.downloadAttempted = true;
            LOGGER.info("Downloading document {}/{}: id='{}', title='{}'", index, documents.size(), pending.id,
                    pending.title);
            try {
                download(pending, client, store);
            } catch (SessionExpiredException | AuthenticationException | StorageException exception) {
                throw exception;
            } catch (CrawlerException exception) {
                if (Thread.currentThread().isInterrupted()) {
                    throw exception;
                }
                skip(pending, store, exception.getMessage());
            }
        }
    }

    private void download(PendingDocument pending, AlcoHttpClient client, SnapshotStore store) {
        HttpResult result = client.get(URI.create(pending.href));
        byte[] bytes = result.body();
        if (!isPdf(bytes, result.contentType())) {
            skip(pending, store, "response is not a PDF (content type: " + contentType(result) + ")");
            return;
        }
        pending.sha256 = sha256(bytes);
        pending.storedFile = store.writeAttachment(pending.section, pending.period, pending.id, bytes);
        pending.contentType = normalizedContentType(result);
        pending.size = bytes.length;
        LOGGER.info("Stored document: id='{}', bytes={}", pending.id, bytes.length);
    }

    private SectionData attachStoredFiles(SectionData section, Map<String, String> storedFiles) {
        List<TableData> tables = section.tables().stream()
                .map(table -> new TableData(table.headers(), table.rows().stream()
                        .map(row -> row.stream()
                                .map(cell -> new CellData(cell.text(), attachStoredFiles(cell.links(), storedFiles)))
                                .toList())
                        .toList()))
                .toList();
        List<ItemData> items = section.items().stream()
                .map(item -> new ItemData(item.heading(), item.content(), attachStoredFiles(item.links(), storedFiles)))
                .toList();
        return new SectionData(section.section(), section.contractId(), section.sourceUrl(), section.capturedAt(),
                section.title(), section.pageText(), section.context(), section.fields(), tables, items,
                attachStoredFiles(section.links(), storedFiles), section.documents());
    }

    private List<LinkData> attachStoredFiles(List<LinkData> links, Map<String, String> storedFiles) {
        return links.stream()
                .map(link -> new LinkData(link.text(), link.href(), storedFiles.get(link.href())))
                .toList();
    }

    private void skip(PendingDocument pending, SnapshotStore store, String reason) {
        String warning = "Attachment " + pending.id + " was skipped: " + safeReason(reason);
        store.addWarning(warning);
        LOGGER.warn("{}", warning);
    }

    private String normalizedContentType(HttpResult result) {
        return result.contentType().isBlank() ? "application/pdf" : result.contentType().split(";", 2)[0].trim();
    }

    private String contentType(HttpResult result) {
        return result.contentType().isBlank() ? "unknown" : result.contentType().split(";", 2)[0].trim();
    }

    private String safeReason(String reason) {
        return reason == null || reason.isBlank() ? "download failed" : reason;
    }

    private boolean isPdf(byte[] bytes, String contentType) {
        boolean magic = bytes.length >= 5 && bytes[0] == '%' && bytes[1] == 'P' && bytes[2] == 'D'
                && bytes[3] == 'F' && bytes[4] == '-';
        String normalizedType = contentType == null ? "" : contentType.toLowerCase();
        return magic && (normalizedType.contains("application/pdf")
                || normalizedType.contains("application/octet-stream") || normalizedType.isBlank());
    }

    private String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }

    private static final class PendingDocument {

        private final String id;
        private String title;
        private final String href;
        private final String section;
        private final String period;
        private final Set<DocumentSource> sources = new LinkedHashSet<>();
        private boolean downloadAttempted;
        private String contentType;
        private long size;
        private String sha256;
        private String storedFile;

        private PendingDocument(String id, String title, String href, String section, String period) {
            this.id = id;
            this.title = title;
            this.href = href;
            this.section = section;
            this.period = period;
        }
    }
}
