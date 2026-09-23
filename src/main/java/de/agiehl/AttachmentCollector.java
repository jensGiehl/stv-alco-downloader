package de.agiehl;

import java.net.URI;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class AttachmentCollector {

    private static final Logger LOGGER = LoggerFactory.getLogger(AttachmentCollector.class);

    private final Map<String, PendingDocument> documents = new LinkedHashMap<>();

    void register(SectionData section) {
        for (DiscoveredDocument document : section.documents()) {
            PendingDocument pending = documents.computeIfAbsent(document.id(), ignored -> new PendingDocument(
                    document.id(), document.title(), document.href()));
            pending.sources.add(new DocumentSource(document.sourceSection(), document.contractId(),
                    document.sourceUrl(), document.title(), document.href()));
            if (pending.title.isBlank() && !document.title().isBlank()) {
                pending.title = document.title();
            }
        }
    }

    List<DocumentData> downloadAll(AlcoHttpClient client, SnapshotStore store) {
        List<DocumentData> downloaded = new ArrayList<>();
        int index = 0;
        for (PendingDocument pending : documents.values()) {
            index++;
            LOGGER.info("Downloading document {}/{}: id='{}', title='{}'", index, documents.size(), pending.id,
                    pending.title);
            try {
                download(pending, client, store).ifPresent(downloaded::add);
            } catch (SessionExpiredException | AuthenticationException | StorageException exception) {
                throw exception;
            } catch (CrawlerException exception) {
                if (Thread.currentThread().isInterrupted()) {
                    throw exception;
                }
                skip(pending, store, exception.getMessage());
            }
        }
        return List.copyOf(downloaded);
    }

    int size() {
        return documents.size();
    }

    private Optional<DocumentData> download(PendingDocument pending, AlcoHttpClient client, SnapshotStore store) {
        HttpResult result = client.get(URI.create(pending.href));
        byte[] bytes = result.body();
        if (!isPdf(bytes, result.contentType())) {
            skip(pending, store, "response is not a PDF (content type: " + contentType(result) + ")");
            return Optional.empty();
        }
        String sha256 = sha256(bytes);
        String storedFile = store.writeAttachment(sha256, bytes);
        String contentType = normalizedContentType(result);
        LOGGER.info("Stored document: id='{}', bytes={}", pending.id, bytes.length);
        return Optional.of(new DocumentData(pending.id, pending.title, pending.href, contentType,
                bytes.length, sha256, storedFile, List.copyOf(pending.sources)));
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
        private final Set<DocumentSource> sources = new LinkedHashSet<>();

        private PendingDocument(String id, String title, String href) {
            this.id = id;
            this.title = title;
            this.href = href;
        }
    }
}
