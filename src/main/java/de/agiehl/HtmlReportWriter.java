package de.agiehl;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

final class HtmlReportWriter {

    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("dd.MM.uuuu, HH:mm 'Uhr'")
            .withZone(ZoneId.of("Europe/Berlin"));
    private static final String BOOTSTRAP_VERSION = "5.3.8";
    private static final String FAVICON = "data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 64 64'%3E%3Crect width='64' height='64' rx='16' fill='%23101c2c'/%3E%3Cpath d='M18 17h28v30H18z' fill='%231cba8b'/%3E%3Cpath d='M24 25h16M24 32h16M24 39h10' stroke='white' stroke-width='4' stroke-linecap='round'/%3E%3C/svg%3E";

    private final Path root;
    private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
    private final List<ContractReference> contracts = new ArrayList<>();
    private final List<StoredSection> sections = new ArrayList<>();
    private final List<DocumentData> documents = new ArrayList<>();

    HtmlReportWriter(Path root) {
        this.root = root;
    }

    void contracts(List<ContractReference> values) {
        contracts.clear();
        contracts.addAll(values);
    }

    void section(SectionData value, String rawPath) {
        sections.add(new StoredSection(value, rawPath));
    }

    void documents(List<DocumentData> values) {
        documents.clear();
        documents.addAll(values);
    }

    void write(Map<String, Object> manifest) {
        try {
            copyAssets(root);
            writeFile(root.resolve("index.html"), overview(manifest));
            writeFile(root.resolve("documents.html"), documentsPage(manifest));
            for (ContractReference contract : contracts) {
                Path directory = root.resolve("contracts").resolve(sanitize(contract.id()));
                writeFile(directory.resolve("index.html"), contractPage(contract, manifest));
            }
            writeCatalog();
        } catch (IOException exception) {
            throw new StorageException("Cannot write HTML report", exception);
        }
    }

    private void writeCatalog() throws IOException {
        Path outputRoot = root.getParent();
        copyAssets(outputRoot);
        List<SnapshotSummary> snapshots;
        try (var paths = Files.list(outputRoot)) {
            snapshots = paths.filter(Files::isDirectory)
                    .filter(path -> Files.isRegularFile(path.resolve("manifest.json")))
                    .map(this::readSummary)
                    .flatMap(java.util.Optional::stream)
                    .sorted(java.util.Comparator.comparing(SnapshotSummary::startedAt,
                            java.util.Comparator.nullsLast(java.util.Comparator.reverseOrder())))
                    .toList();
        }
        StringBuilder content = new StringBuilder("""
                <header class="site-header"><nav class="container-xl navbar"><span class="brand"><span class="brand-mark">A</span><span><strong>ALCO</strong><small>Datenarchiv</small></span></span></nav></header>
                <main class="container-xl py-4 py-lg-5">
                  <section class="page-intro"><div class="eyebrow">SICHERUNGSARCHIV</div><h1>Ihre ALCO-Snapshots</h1><p>Wählen Sie einen Sicherungslauf, um Verträge, Abrechnungen und Dokumente anzusehen.</p></section>
                  <section class="content-card">
                    <div class="card-heading d-flex flex-column flex-md-row gap-3 justify-content-between align-items-md-center">
                      <div><div class="eyebrow">VERLAUF</div><h2 class="h3 mb-0">Sicherungsläufe</h2></div>
                      <label class="search-box"><span class="visually-hidden">Snapshots durchsuchen</span><input class="form-control" type="search" placeholder="Datum oder Zeitraum suchen …" data-filter-input="snapshots"></label>
                    </div><div class="snapshot-list" data-filter-list="snapshots">
                """);
        if (snapshots.isEmpty()) {
            content.append(emptyState("Noch keine Snapshots", "Nach dem ersten Sicherungslauf erscheint hier der Datenbestand."));
        } else {
            for (SnapshotSummary snapshot : snapshots) {
                boolean complete = "COMPLETE".equals(snapshot.status());
                String search = "%s %s %s".formatted(formatDate(snapshot.startedAt()),
                        periodName(snapshot.period()), snapshot.status());
                content.append("""
                        <a class="snapshot-card" href="%s/index.html" data-filter-item data-search="%s">
                          <span class="snapshot-date"><strong>%s</strong><small>%s</small></span>
                          <span class="snapshot-facts"><span>%d Verträge</span><span>%d Seiten</span><span>%d Dokumente</span></span>
                          <span class="run-status %s"><i></i>%s</span><span class="arrow" aria-hidden="true">→</span>
                        </a>
                        """.formatted(escapeAttribute(snapshot.directory()), escapeAttribute(search),
                        escape(datePart(snapshot.startedAt())), escape(timePart(snapshot.startedAt())),
                        snapshot.contracts(), snapshot.pages(), snapshot.documents(), complete ? "complete" : "failed",
                        complete ? "Vollständig" : "Unvollständig"));
            }
        }
        content.append("</div><div class=\"filter-empty d-none\">Keine passenden Sicherungsläufe gefunden.</div></section></main>")
                .append(footer("."));
        writeFile(outputRoot.resolve("index.html"), document("ALCO-Sicherungsarchiv", ".", content.toString()));
    }

    private java.util.Optional<SnapshotSummary> readSummary(Path directory) {
        try {
            Map<String, Object> manifest = objectMapper.readValue(directory.resolve("manifest.json").toFile(),
                    new TypeReference<>() { });
            return java.util.Optional.of(new SnapshotSummary(directory.getFileName().toString(),
                    valueAsInstant(manifest.get("startedAt")), String.valueOf(manifest.getOrDefault("status", "FAILED")),
                    String.valueOf(manifest.getOrDefault("period", "ALL")), number(manifest.get("contracts")),
                    number(manifest.get("pages")), number(manifest.get("documents"))));
        } catch (IOException | RuntimeException ignored) {
            return java.util.Optional.empty();
        }
    }

    private String overview(Map<String, Object> manifest) {
        long tableRows = sections.stream().flatMap(section -> section.data().tables().stream())
                .mapToLong(table -> table.rows().size()).sum();
        StringBuilder content = new StringBuilder();
        content.append(header("Übersicht", ".", manifest));
        content.append("""
                <main class="container-xl py-4 py-lg-5">
                  <section class="summary-panel mb-4">
                    <div>
                      <div class="eyebrow">ALCO-SNAPSHOT</div>
                      <h1 class="display-title">Ihre Daten auf einen Blick</h1>
                      <p class="summary-copy">Verträge, Abrechnungen und Dokumente dieses Sicherungslaufs – vollständig lokal und ohne Datenübertragung.</p>
                    </div>
                    %s
                  </section>
                  <section class="row g-3 mb-4" aria-label="Kennzahlen">
                    %s%s%s%s
                  </section>
                  <section class="content-card">
                    <div class="card-heading d-flex flex-column flex-md-row gap-3 justify-content-between align-items-md-center">
                      <div><div class="eyebrow">BESTAND</div><h2 class="h3 mb-0">Verträge</h2></div>
                      <label class="search-box"><span class="visually-hidden">Verträge durchsuchen</span><input class="form-control" type="search" placeholder="Vertrag suchen …" data-filter-input="contracts"></label>
                    </div>
                    <div class="contract-grid" data-filter-list="contracts">
                """.formatted(statusBadge(manifest), metric("Verträge", contracts.size(), "contract"),
                metric("Seiten", sections.size(), "page"), metric("Tabellenzeilen", tableRows, "rows"),
                metric("Dokumente", documents.size(), "document")));
        if (contracts.isEmpty()) {
            content.append(emptyState("Noch keine Verträge", "In diesem Snapshot wurden keine Verträge gespeichert."));
        } else {
            Map<String, Long> pageCounts = new LinkedHashMap<>();
            Map<String, Long> documentCounts = new LinkedHashMap<>();
            sections.forEach(section -> pageCounts.merge(section.data().contractId(), 1L, Long::sum));
            documents.stream().flatMap(document -> document.sources().stream())
                    .forEach(source -> documentCounts.merge(source.contractId(), 1L, Long::sum));
            for (ContractReference contract : contracts) {
                String href = "contracts/%s/index.html".formatted(sanitize(contract.id()));
                String search = "%s %s %s".formatted(contract.contractNumber(), contract.description(), contract.id());
                content.append("""
                        <a class="contract-card" href="%s" data-filter-item data-search="%s">
                          <div class="contract-icon" aria-hidden="true">%s</div>
                          <div class="flex-grow-1 min-w-0">
                            <div class="contract-number">%s</div>
                            <h3>%s</h3>
                            <div class="contract-meta"><span>%d Seiten</span><span>%d Dokumente</span></div>
                          </div>
                          <span class="arrow" aria-hidden="true">→</span>
                        </a>
                        """.formatted(escapeAttribute(href), escapeAttribute(search),
                        initials(contract.description(), contract.contractNumber()), escape(contract.contractNumber()),
                        escape(fallback(contract.description(), "Vertrag " + contract.id())),
                        pageCounts.getOrDefault(contract.id(), 0L), documentCounts.getOrDefault(contract.id(), 0L)));
            }
        }
        content.append("</div><div class=\"filter-empty d-none\">Keine passenden Verträge gefunden.</div></section></main>");
        content.append(footer("."));
        return document("ALCO-Snapshot – Übersicht", ".", content.toString());
    }

    private String documentsPage(Map<String, Object> manifest) {
        long bytes = documents.stream().mapToLong(DocumentData::size).sum();
        StringBuilder content = new StringBuilder();
        content.append(header("Dokumente", ".", manifest));
        content.append("""
                <main class="container-xl py-4 py-lg-5">
                  <section class="page-intro d-flex flex-column flex-lg-row gap-3 justify-content-between align-items-lg-end">
                    <div><div class="eyebrow">DOKUMENTARCHIV</div><h1>Gespeicherte Dokumente</h1><p>Alle PDFs aus diesem Snapshot, zusammengeführt ohne Dubletten.</p></div>
                    <div class="document-total"><strong>%d</strong><span>Dateien · %s</span></div>
                  </section>
                  <section class="content-card">
                    <div class="card-heading"><label class="search-box w-100"><span class="visually-hidden">Dokumente durchsuchen</span><input class="form-control" type="search" placeholder="Titel, Bereich oder Vertrag suchen …" data-filter-input="documents"></label></div>
                    <div class="document-list" data-filter-list="documents">
                """.formatted(documents.size(), formatBytes(bytes)));
        if (documents.isEmpty()) {
            content.append(emptyState("Keine Dokumente", "In diesem Snapshot wurden keine PDF-Dokumente gespeichert."));
        } else {
            for (DocumentData document : documents) {
                String sources = document.sources().stream().map(DocumentSource::title).filter(title -> !title.isBlank())
                        .distinct().reduce((left, right) -> left + " · " + right).orElse("Ohne Bereichsangabe");
                String contracts = document.sources().stream().map(DocumentSource::contractId).distinct()
                        .reduce((left, right) -> left + ", " + right).orElse("–");
                String search = "%s %s %s %s".formatted(document.title(), document.id(), sources, contracts);
                content.append("""
                        <article class="document-row" data-filter-item data-search="%s">
                          <div class="pdf-icon" aria-hidden="true">PDF</div>
                          <div class="flex-grow-1 min-w-0"><h2>%s</h2><p>%s</p><div class="document-meta"><span>%s</span><span>ID %s</span><span>Vertrag %s</span></div></div>
                          <a class="btn btn-sm btn-primary" href="%s" target="_blank">Öffnen</a>
                        </article>
                        """.formatted(escapeAttribute(search), escape(fallback(document.title(), "Dokument")),
                        escape(sources), formatBytes(document.size()), escape(document.id()), escape(contracts),
                        escapeAttribute(document.storedFile())));
            }
        }
        content.append("</div><div class=\"filter-empty d-none\">Keine passenden Dokumente gefunden.</div></section></main>");
        content.append(footer("."));
        return document("ALCO-Snapshot – Dokumente", ".", content.toString());
    }

    private String contractPage(ContractReference contract, Map<String, Object> manifest) {
        List<StoredSection> contractSections = sections.stream()
                .filter(section -> section.data().contractId().equals(contract.id())).toList();
        long documentCount = documents.stream().flatMap(document -> document.sources().stream())
                .filter(source -> source.contractId().equals(contract.id())).count();
        StringBuilder content = new StringBuilder();
        content.append(header("Vertrag", "../..", manifest));
        content.append("""
                <main class="container-xl py-4 py-lg-5">
                  <a class="back-link" href="../../index.html">← Alle Verträge</a>
                  <section class="contract-header">
                    <div class="contract-icon large" aria-hidden="true">%s</div>
                    <div><div class="eyebrow">VERTRAG %s</div><h1>%s</h1><p>%d gespeicherte Seiten · %d Dokumentverweise</p></div>
                  </section>
                  <section class="content-card">
                    <div class="card-heading d-flex flex-column flex-md-row gap-3 justify-content-between align-items-md-center">
                      <div><div class="eyebrow">INHALTE</div><h2 class="h3 mb-0">Bereiche und Zeiträume</h2></div>
                      <label class="search-box"><span class="visually-hidden">Inhalte durchsuchen</span><input class="form-control" type="search" placeholder="Inhalte filtern …" data-filter-input="sections"></label>
                    </div>
                    <div class="section-stack" data-filter-list="sections">
                """.formatted(initials(contract.description(), contract.contractNumber()),
                escape(contract.contractNumber()), escape(fallback(contract.description(), "Vertrag " + contract.id())),
                contractSections.size(), documentCount));
        if (contractSections.isEmpty()) {
            content.append(emptyState("Keine Inhalte", "Für diesen Vertrag wurden keine Seiten gespeichert."));
        } else {
            for (int index = 0; index < contractSections.size(); index++) {
                content.append(sectionCard(contractSections.get(index), index + 1));
            }
        }
        content.append("</div><div class=\"filter-empty d-none\">Keine passenden Inhalte gefunden.</div></section></main>");
        content.append(footer("../.."));
        return document("%s – ALCO-Snapshot".formatted(contract.contractNumber()), "../..", content.toString());
    }

    private String sectionCard(StoredSection stored, int number) {
        SectionData section = stored.data();
        String period = section.context().getOrDefault("period", "Ohne Zeitraum");
        String search = section.title() + " " + section.section() + " " + period + " " + section.pageText();
        StringBuilder result = new StringBuilder("""
                <article class="section-card" data-filter-item data-search="%s">
                  <details%s>
                    <summary><span class="section-count">%02d</span><span class="flex-grow-1"><span class="section-label">%s</span><strong>%s</strong></span><span class="period-pill">%s</span><span class="chevron" aria-hidden="true"></span></summary>
                    <div class="section-body">
                """.formatted(escapeAttribute(search), number == 1 ? " open" : "", number,
                escape(sectionName(section.section())), escape(fallback(section.title(), sectionName(section.section()))),
                escape(period)));
        if (!section.context().isEmpty() || !section.fields().isEmpty()) {
            result.append("<dl class=\"field-grid\">");
            section.context().forEach((key, value) -> result.append(definition(key, value)));
            section.fields().forEach((key, value) -> result.append(definition(key, value)));
            result.append("</dl>");
        }
        for (TableData table : section.tables()) {
            result.append(table(table));
        }
        for (ItemData item : section.items()) {
            result.append("<section class=\"item-card\"><h3>").append(escape(item.heading())).append("</h3><p>")
                    .append(formatText(item.content())).append("</p>").append(links(item.links())).append("</section>");
        }
        if (section.links().stream().anyMatch(link -> link.storedFile() != null)) {
            result.append("<section class=\"link-block\"><h3>Verweise</h3>").append(links(section.links())).append("</section>");
        }
        if (!section.pageText().isBlank()) {
            result.append("<details class=\"page-text\"><summary>Vollständigen Seitentext anzeigen</summary><p>")
                    .append(formatText(section.pageText())).append("</p></details>");
        }
        result.append("<div class=\"source-actions\">");
        if (stored.rawPath() != null) {
            result.append("<a href=\"../../").append(escapeAttribute(stored.rawPath()))
                    .append("\" target=\"_blank\">Originalseite öffnen</a>");
        }
        result.append("<span>Erfasst: ").append(formatDate(section.capturedAt())).append("</span></div></div></details></article>");
        return result.toString();
    }

    private String table(TableData table) {
        StringBuilder result = new StringBuilder("<div class=\"table-responsive table-shell\"><table class=\"table table-hover align-middle\">");
        if (!table.headers().isEmpty()) {
            result.append("<thead><tr>");
            table.headers().forEach(header -> result.append("<th scope=\"col\">").append(escape(header)).append("</th>"));
            result.append("</tr></thead>");
        }
        result.append("<tbody>");
        for (List<CellData> row : table.rows()) {
            result.append("<tr>");
            for (CellData cell : row) {
                result.append("<td><span>").append(formatText(cell.text())).append("</span>")
                        .append(links(cell.links())).append("</td>");
            }
            result.append("</tr>");
        }
        return result.append("</tbody></table></div>").toString();
    }

    private String links(List<LinkData> values) {
        List<LinkData> internalLinks = values.stream().filter(link -> link.storedFile() != null).toList();
        if (internalLinks.isEmpty()) {
            return "";
        }
        StringBuilder result = new StringBuilder("<div class=\"data-links\">");
        for (LinkData link : internalLinks) {
            result.append("<a href=\"../../").append(escapeAttribute(link.storedFile()))
                    .append("\" target=\"_blank\">")
                    .append(escape(fallback(link.text(), "PDF öffnen"))).append("</a>");
        }
        return result.append("</div>").toString();
    }

    private String header(String current, String base, Map<String, Object> manifest) {
        return """
                <header class="site-header">
                  <nav class="container-xl navbar navbar-expand" aria-label="Hauptnavigation">
                    <a class="brand" href="%s/index.html"><span class="brand-mark">A</span><span><strong>ALCO</strong><small>Datenarchiv</small></span></a>
                    <div class="nav-links ms-auto">
                      <a class="%s" href="%s/index.html">Übersicht</a>
                      <a class="%s" href="%s/documents.html">Dokumente</a>
                    </div>
                  </nav>
                  <div class="snapshot-strip"><div class="container-xl"><span>Snapshot vom %s</span><span class="dot"></span><span>%s</span></div></div>
                </header>
                """.formatted(base, current.equals("Übersicht") ? "active" : "", base,
                current.equals("Dokumente") ? "active" : "", base, formatDate(valueAsInstant(manifest.get("startedAt"))),
                escape(periodName(String.valueOf(manifest.getOrDefault("period", "ALL")))));
    }

    private String footer(String base) {
        return """
                <footer class="container-xl site-footer"><span>Lokaler ALCO-Snapshot</span><span>Die Daten bleiben auf diesem Gerät.</span></footer>
                <script src="%s/assets/bootstrap.bundle.min.js"></script><script src="%s/assets/report.js"></script>
                """.formatted(base, base);
    }

    private String document(String title, String base, String content) {
        return """
                <!doctype html>
                <html lang="de" data-bs-theme="light">
                <head>
                  <meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">
                  <meta name="description" content="Lokale Ansicht eines ALCO-Datensnapshots">
                  <title>%s</title><link rel="icon" type="image/svg+xml" href="%s">
                  <link rel="stylesheet" href="%s/assets/bootstrap.min.css"><link rel="stylesheet" href="%s/assets/report.css">
                </head><body>%s</body></html>
                """.formatted(escape(title), FAVICON, base, base, content);
    }

    private void copyAssets(Path siteRoot) throws IOException {
        Path assets = siteRoot.resolve("assets");
        Files.createDirectories(assets);
        copyResource("/META-INF/resources/webjars/bootstrap/%s/css/bootstrap.min.css".formatted(BOOTSTRAP_VERSION),
                assets.resolve("bootstrap.min.css"));
        copyResource("/META-INF/resources/webjars/bootstrap/%s/js/bootstrap.bundle.min.js".formatted(BOOTSTRAP_VERSION),
                assets.resolve("bootstrap.bundle.min.js"));
        copyResource("/report/report.css", assets.resolve("report.css"));
        copyResource("/report/report.js", assets.resolve("report.js"));
    }

    private void copyResource(String resource, Path target) throws IOException {
        try (InputStream input = HtmlReportWriter.class.getResourceAsStream(resource)) {
            if (input == null) {
                throw new IOException("Missing report resource " + resource);
            }
            Files.copy(input, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private void writeFile(Path path, String value) throws IOException {
        Files.createDirectories(path.getParent());
        Files.writeString(path, value, StandardCharsets.UTF_8);
    }

    private String metric(String label, long value, String type) {
        return "<div class=\"col-6 col-lg-3\"><div class=\"metric-card %s\"><span>%s</span><strong>%d</strong></div></div>"
                .formatted(type, escape(label), value);
    }

    private String statusBadge(Map<String, Object> manifest) {
        boolean complete = "COMPLETE".equals(manifest.get("status"));
        String status = complete ? "Vollständig" : "Unvollständig";
        return "<div class=\"status-badge %s\"><span></span>%s</div>".formatted(complete ? "complete" : "failed", status);
    }

    private String definition(String key, String value) {
        return "<div><dt>%s</dt><dd>%s</dd></div>".formatted(escape(fieldName(key)), escape(value));
    }

    private String emptyState(String title, String text) {
        return "<div class=\"empty-state\"><div>○</div><h3>%s</h3><p>%s</p></div>".formatted(escape(title), escape(text));
    }

    private String initials(String primary, String secondary) {
        String source = fallback(primary, secondary).strip();
        if (source.isEmpty()) {
            return "A";
        }
        String[] words = source.split("\\s+");
        String first = words[0].substring(0, 1);
        String second = words.length > 1 ? words[1].substring(0, 1) : "";
        return escape((first + second).toUpperCase());
    }

    private String sectionName(String value) {
        return switch (value) {
            case "home" -> "Startseite";
            case "vertragszahlung" -> "Zahlungsdaten";
            case "einheit" -> "Einheit";
            case "infosend" -> "Mitteilungen";
            case "infosend-detail" -> "Mitteilungsdetail";
            case "mda-objekte" -> "Objektinformationen";
            case "showinfo" -> "Reparaturen";
            case "beschluss" -> "Beschlüsse";
            case "doc-beirat" -> "Beiratsdokumente";
            case "obj-lieferanten" -> "Lieferanten";
            case "obj-lieferanten-detail" -> "Lieferantendetail";
            case "kontoauszug" -> "Kontoauszug";
            case "mda-salden" -> "Salden";
            case "mda-salden-kontoauszug" -> "Salden-Kontoauszug";
            case "settlement-overview" -> "Abrechnungsübersicht";
            case "settlement-detail" -> "Abrechnungsdetail";
            default -> value;
        };
    }

    private String fieldName(String value) {
        return switch (value) {
            case "period" -> "Zeitraum";
            case "granularity" -> "Auflösung";
            case "requestedPeriod" -> "Gewählter Modus";
            case "periodValue" -> "Zeitraum-ID";
            case "supplierId" -> "Lieferanten-ID";
            case "accountId" -> "Konto-ID";
            case "accountName" -> "Bezeichnung";
            case "accountType" -> "Kontotyp";
            default -> value;
        };
    }

    private String periodName(String value) {
        return switch (value) {
            case "CURRENT_MONTH" -> "Aktueller Monat";
            case "CURRENT_YEAR" -> "Aktuelles Jahr";
            default -> "Alle Zeiträume";
        };
    }

    private String formatText(String value) {
        return escape(value).replace("\r\n", "<br>").replace("\n", "<br>");
    }

    private String formatDate(Instant value) {
        return value == null ? "–" : DATE_TIME.format(value);
    }

    private String datePart(Instant value) {
        return value == null ? "Unbekannt" : DateTimeFormatter.ofPattern("dd.MM.uuuu").withZone(ZoneId.of("Europe/Berlin")).format(value);
    }

    private String timePart(Instant value) {
        return value == null ? "Ohne Zeitangabe" : DateTimeFormatter.ofPattern("HH:mm 'Uhr'").withZone(ZoneId.of("Europe/Berlin")).format(value);
    }

    private long number(Object value) {
        return value instanceof Number number ? number.longValue() : 0;
    }

    private Instant valueAsInstant(Object value) {
        if (value instanceof Instant instant) {
            return instant;
        }
        try {
            return value == null ? null : Instant.parse(value.toString());
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private String formatBytes(long value) {
        if (value < 1024) {
            return value + " B";
        }
        if (value < 1024 * 1024) {
            return "%.1f KB".formatted(value / 1024.0);
        }
        return "%.1f MB".formatted(value / (1024.0 * 1024.0));
    }

    private String fallback(String value, String alternative) {
        return value == null || value.isBlank() ? alternative : value;
    }

    private String escape(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&#39;");
    }

    private String escapeAttribute(String value) {
        return escape(value).replace("`", "&#96;");
    }

    private String sanitize(String value) {
        String sanitized = value.toLowerCase().replaceAll("[^a-z0-9._-]+", "-").replaceAll("^-+|-+$", "");
        return sanitized.isBlank() ? "unnamed" : sanitized;
    }

    private record StoredSection(SectionData data, String rawPath) {
    }

    private record SnapshotSummary(String directory, Instant startedAt, String status, String period, long contracts,
                                   long pages, long documents) {
    }
}
