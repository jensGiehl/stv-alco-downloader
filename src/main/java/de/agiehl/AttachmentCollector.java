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
import java.util.Set;

final class AttachmentCollector {

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
        for (PendingDocument pending : documents.values()) {
            HttpResult result = client.get(URI.create(pending.href));
            byte[] bytes = result.body();
            if (!isPdf(bytes, result.contentType())) {
                throw new CrawlerException("Attachment " + pending.id + " did not return a PDF");
            }
            String sha256 = sha256(bytes);
            String storedFile = store.writeAttachment(sha256, bytes);
            String contentType = result.contentType().isBlank() ? "application/pdf"
                    : result.contentType().split(";", 2)[0].trim();
            downloaded.add(new DocumentData(pending.id, pending.title, pending.href, contentType, bytes.length,
                    sha256, storedFile, List.copyOf(pending.sources)));
        }
        return List.copyOf(downloaded);
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
