package de.agiehl;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Map;

record HttpResult(URI uri, int statusCode, String contentType, byte[] body) {

    String bodyAsString() {
        return new String(body, java.nio.charset.StandardCharsets.UTF_8);
    }
}

record ContractReference(String id, String contractNumber, String description, URI selectionUri) {
}

record LinkData(String text, String href) {
}

record CellData(String text, List<LinkData> links) {
}

record TableData(List<String> headers, List<List<CellData>> rows) {
}

record ItemData(String heading, String content, List<LinkData> links) {
}

record DiscoveredDocument(String id, String title, String href, String sourceSection, String sourceUrl,
                          String contractId) {
}

record SectionData(String section, String contractId, String sourceUrl, Instant capturedAt,
                   String title, String pageText, Map<String, String> context, Map<String, String> fields,
                   List<TableData> tables, List<ItemData> items, List<LinkData> links,
                   List<DiscoveredDocument> documents) {
}

record SelectOption(String value, String label, boolean selected) {
}

record DocumentSource(String section, String contractId, String sourceUrl, String title, String href) {
}

record DocumentData(String id, String title, String originalHref, String contentType, long size,
                    String sha256, String storedFile, List<DocumentSource> sources) {
}
