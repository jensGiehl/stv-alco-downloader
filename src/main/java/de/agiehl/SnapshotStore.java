package de.agiehl;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

final class SnapshotStore {

    private static final DateTimeFormatter DIRECTORY_FORMAT = DateTimeFormatter
            .ofPattern("uuuu-MM-dd_HH_mm").withZone(ZoneId.of("Europe/Berlin"));
    private static final DateTimeFormatter DATE_DIRECTORY_FORMAT = DateTimeFormatter
            .ofPattern("uuuu-MM-dd").withZone(ZoneId.of("Europe/Berlin"));
    private static final DateTimeFormatter FILE_TIMESTAMP_FORMAT = DateTimeFormatter
            .ofPattern("uuuuMMdd_HHmmss").withZone(ZoneId.of("Europe/Berlin"));
    private static final Pattern YEAR = Pattern.compile("(?<!\\d)(\\d{4})(?!\\d)");

    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final Path root;
    private final Path rawDirectory;
    private final Path dataDirectory;
    private final Path attachmentDirectory;
    private final HtmlReportWriter htmlReportWriter;
    private final AtomicInteger sequence = new AtomicInteger();
    private final Map<String, Object> manifest = new LinkedHashMap<>();
    private final List<String> warnings = new ArrayList<>();
    private int pageCount;

    SnapshotStore(AlcoProperties properties, Clock clock) {
        this.clock = clock;
        this.objectMapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .enable(SerializationFeature.INDENT_OUTPUT);
        Instant startedAt = clock.instant();
        this.root = createSnapshotDirectory(properties.getOutputDir().toAbsolutePath().normalize(), startedAt);
        this.rawDirectory = root.resolve("raw");
        this.dataDirectory = root.resolve("data");
        this.attachmentDirectory = root.resolve("files");
        this.htmlReportWriter = new HtmlReportWriter(root);
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

    private static Path createSnapshotDirectory(Path outputDirectory, Instant startedAt) {
        try {
            Files.createDirectories(outputDirectory);
            String directoryName = DIRECTORY_FORMAT.format(startedAt);
            for (int suffix = 1; ; suffix++) {
                Path candidate = outputDirectory.resolve(suffix == 1 ? directoryName : directoryName + "_" + suffix);
                try {
                    return Files.createDirectory(candidate);
                } catch (FileAlreadyExistsException ignored) {
                }
            }
        } catch (IOException exception) {
            throw new StorageException("Cannot create snapshot directory", exception);
        }
    }

    Path root() {
        return root;
    }

    void writeContracts(List<ContractReference> contracts) {
        writeJson(dataDirectory.resolve("contracts.json"), contracts);
        htmlReportWriter.contracts(contracts);
        manifest.put("contracts", contracts.size());
        writeManifest();
    }

    void writeSection(SectionData section, HttpResult rawResult) {
        int number = sequence.incrementAndGet();
        String contract = sanitize(section.contractId().isBlank() ? "global" : section.contractId());
        String page = sanitize(section.section());
        String period = periodDirectory(section.context().get("period"), section.capturedAt());
        String prefix = "%04d".formatted(number);
        Path pageDirectory = dataDirectory.resolve("pages").resolve(page).resolve(period).resolve(contract);
        writeJson(pageDirectory.resolve(prefix + ".json"), section);
        Path rawFile = writeRaw(page, period, prefix, contract, rawResult);
        htmlReportWriter.section(section, rawFile == null ? null : root.relativize(rawFile).toString().replace('\\', '/'));
        pageCount++;
    }

    void writeDocuments(List<DocumentData> documents) {
        writeJson(dataDirectory.resolve("documents.json"), documents);
        htmlReportWriter.documents(documents);
        manifest.put("documents", documents.size());
        manifest.put("attachments", documents.stream().map(DocumentData::storedFile).distinct().count());
        writeManifest();
    }

    String writeAttachment(String sha256, byte[] bytes) {
        return writeAttachment("documents", "", sha256, bytes);
    }

    String writeAttachment(String section, String period, String id, byte[] bytes) {
        Path directory = attachmentDirectory.resolve(sanitize(section))
                .resolve(periodDirectory(period, clock.instant()));
        String baseName = sanitize(id);
        Path file = directory.resolve(baseName + ".pdf");
        if (Files.exists(file)) {
            String timestamp = FILE_TIMESTAMP_FORMAT.format(clock.instant());
            file = directory.resolve(baseName + "_" + timestamp + ".pdf");
            for (int suffix = 2; Files.exists(file); suffix++) {
                file = directory.resolve(baseName + "_" + timestamp + "_" + suffix + ".pdf");
            }
        }
        ensureInsideSnapshot(file);
        try {
            Files.createDirectories(directory);
            Files.write(file, bytes);
        } catch (IOException exception) {
            throw new StorageException("Cannot write attachment", exception);
        }
        return root.relativize(file).toString().replace('\\', '/');
    }

    void addWarning(String warning) {
        warnings.add(warning);
    }

    int warningCount() {
        return warnings.size();
    }

    int pageCount() {
        return pageCount;
    }

    void complete(Instant finishedAt) {
        manifest.put("status", "COMPLETE");
        manifest.put("finishedAt", finishedAt);
        manifest.put("pages", pageCount);
        manifest.put("warnings", List.copyOf(warnings));
        writeManifest();
        htmlReportWriter.write(manifest);
    }

    void fail(Instant finishedAt, String reason) {
        manifest.put("status", "FAILED");
        manifest.put("finishedAt", finishedAt);
        manifest.put("pages", pageCount);
        manifest.put("warnings", List.copyOf(warnings));
        manifest.put("failure", reason);
        writeManifest();
        htmlReportWriter.write(manifest);
    }

    private Path writeRaw(String page, String period, String prefix, String contract, HttpResult result) {
        if (!isHtml(result)) {
            return null;
        }
        Path directory = rawDirectory.resolve(page).resolve(period).resolve(contract);
        Path htmlFile = directory.resolve(prefix + ".html");
        writeString(htmlFile, result.bodyAsString());
        Map<String, Object> metadata = Map.of(
                "sourceUrl", result.uri().toString(),
                "statusCode", result.statusCode(),
                "contentType", result.contentType());
        writeJson(directory.resolve(prefix + ".meta.json"), metadata);
        return htmlFile;
    }

    private boolean isHtml(HttpResult result) {
        String contentType = result.contentType().toLowerCase();
        String body = result.bodyAsString().stripLeading().toLowerCase();
        return contentType.contains("html") || body.startsWith("<!doctype html") || body.startsWith("<html");
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

    private String periodDirectory(String period, Instant capturedAt) {
        if (period != null && !period.isBlank()) {
            Matcher matcher = YEAR.matcher(period);
            List<String> years = new ArrayList<>();
            while (matcher.find()) {
                years.add(matcher.group(1));
            }
            if (!years.isEmpty() && years.stream().distinct().count() == 1) {
                return years.getFirst();
            }
            String sanitized = sanitize(period);
            if (!"unnamed".equals(sanitized)) {
                return sanitized;
            }
        }
        return DATE_DIRECTORY_FORMAT.format(capturedAt);
    }
}
