package de.agiehl;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

final class SnapshotStore {

    private static final DateTimeFormatter DIRECTORY_FORMAT = DateTimeFormatter
            .ofPattern("uuuuMMdd-HHmmss-SSS'Z'").withZone(ZoneOffset.UTC);

    private final ObjectMapper objectMapper;
    private final Path root;
    private final Path rawDirectory;
    private final Path dataDirectory;
    private final Path attachmentDirectory;
    private final AtomicInteger sequence = new AtomicInteger();
    private final Map<String, Object> manifest = new LinkedHashMap<>();
    private final List<String> warnings = new ArrayList<>();
    private int pageCount;

    SnapshotStore(AlcoProperties properties, Clock clock) {
        this.objectMapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .enable(SerializationFeature.INDENT_OUTPUT);
        Instant startedAt = clock.instant();
        this.root = properties.getOutputDir().toAbsolutePath().normalize()
                .resolve(DIRECTORY_FORMAT.format(startedAt));
        this.rawDirectory = root.resolve("raw");
        this.dataDirectory = root.resolve("data");
        this.attachmentDirectory = root.resolve("attachments");
        try {
            Files.createDirectories(rawDirectory);
            Files.createDirectories(dataDirectory);
            Files.createDirectories(attachmentDirectory);
        } catch (IOException exception) {
            throw new StorageException("Cannot create snapshot directory", exception);
        }
        manifest.put("schemaVersion", 1);
        manifest.put("status", "RUNNING");
        manifest.put("startedAt", startedAt);
        manifest.put("baseUrl", properties.getBaseUrl().toString());
        manifest.put("period", properties.getPeriod().name());
        writeManifest();
    }

    Path root() {
        return root;
    }

    void writeContracts(List<ContractReference> contracts) {
        writeJson(dataDirectory.resolve("contracts.json"), contracts);
        manifest.put("contracts", contracts.size());
        writeManifest();
    }

    void writeSection(SectionData section, HttpResult rawResult) {
        int number = sequence.incrementAndGet();
        String contract = sanitize(section.contractId().isBlank() ? "global" : section.contractId());
        String prefix = "%04d-%s".formatted(number, sanitize(section.section()));
        Path contractDirectory = dataDirectory.resolve("contracts").resolve(contract);
        writeJson(contractDirectory.resolve(prefix + ".json"), section);
        writeRaw(prefix, contract, rawResult);
        pageCount++;
    }

    void writeDocuments(List<DocumentData> documents) {
        writeJson(dataDirectory.resolve("documents.json"), documents);
        manifest.put("documents", documents.size());
        manifest.put("attachments", documents.stream().map(DocumentData::storedFile).distinct().count());
        writeManifest();
    }

    String writeAttachment(String sha256, byte[] bytes) {
        Path file = attachmentDirectory.resolve(sha256 + ".pdf");
        if (!Files.exists(file)) {
            try {
                Files.write(file, bytes);
            } catch (IOException exception) {
                throw new StorageException("Cannot write attachment", exception);
            }
        }
        return root.relativize(file).toString().replace('\\', '/');
    }

    void addWarning(String warning) {
        warnings.add(warning);
    }

    void complete(Instant finishedAt) {
        manifest.put("status", "COMPLETE");
        manifest.put("finishedAt", finishedAt);
        manifest.put("pages", pageCount);
        manifest.put("warnings", List.copyOf(warnings));
        writeManifest();
    }

    void fail(Instant finishedAt, String reason) {
        manifest.put("status", "FAILED");
        manifest.put("finishedAt", finishedAt);
        manifest.put("pages", pageCount);
        manifest.put("warnings", List.copyOf(warnings));
        manifest.put("failure", reason);
        writeManifest();
    }

    private void writeRaw(String prefix, String contract, HttpResult result) {
        String contentType = result.contentType().toLowerCase();
        if (!(contentType.contains("html") || result.bodyAsString().stripLeading().startsWith("<!doctype html")
                || result.bodyAsString().stripLeading().startsWith("<html"))) {
            return;
        }
        Path directory = rawDirectory.resolve("contracts").resolve(contract);
        writeString(directory.resolve(prefix + ".html"), result.bodyAsString());
        Map<String, Object> metadata = Map.of(
                "sourceUrl", result.uri().toString(),
                "statusCode", result.statusCode(),
                "contentType", result.contentType());
        writeJson(directory.resolve(prefix + ".meta.json"), metadata);
    }

    private void writeManifest() {
        writeJson(root.resolve("manifest.json"), manifest);
    }

    private void writeJson(Path path, Object value) {
        ensureInsideSnapshot(path);
        try {
            Files.createDirectories(path.getParent());
            Path temporary = path.resolveSibling(path.getFileName() + ".tmp");
            objectMapper.writeValue(temporary.toFile(), value);
            moveAtomically(temporary, path);
        } catch (IOException exception) {
            throw new StorageException("Cannot write JSON file " + path.getFileName(), exception);
        }
    }

    private void writeString(Path path, String value) {
        ensureInsideSnapshot(path);
        try {
            Files.createDirectories(path.getParent());
            Files.writeString(path, value, StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new StorageException("Cannot write raw HTML file " + path.getFileName(), exception);
        }
    }

    private void moveAtomically(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private void ensureInsideSnapshot(Path path) {
        if (!path.toAbsolutePath().normalize().startsWith(root)) {
            throw new StorageException("Refusing to write outside the snapshot", new IllegalArgumentException());
        }
    }

    private static String sanitize(String value) {
        String sanitized = value.toLowerCase().replaceAll("[^a-z0-9._-]+", "-")
                .replaceAll("^-+|-+$", "");
        return sanitized.isBlank() ? "unnamed" : sanitized;
    }
}
