package de.agiehl;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HtmlReportWriterTest {

    private static final Instant CAPTURED_AT = Instant.parse("2026-09-24T08:00:00Z");

    @TempDir
    Path temporaryDirectory;

    @Test
    void usesMeaningfulSectionTitlesInTheSummary() throws Exception {
        Path snapshot = temporaryDirectory.resolve("snapshot");
        writeJson(snapshot.resolve("manifest.json"), Map.of(
                "status", "COMPLETE",
                "startedAt", CAPTURED_AT,
                "period", "ALL",
                "contracts", 1,
                "pages", 6,
                "documents", 0));
        writeJson(snapshot.resolve("data/contracts.json"), List.of(
                new ContractReference("0", "71-10-1", "Musterwohnung",
                        URI.create("https://stv.alco-web.de/homeV.php?id=0"))));
        writeSection(snapshot, 1,
                section("home", "ALCO-web", Map.of("Eigentümer", "Max Mustermann"), Map.of()));
        writeSection(snapshot, 2,
                section("vertragszahlung", "Zahlungsinfo", Map.of("IBAN", "DE12 3456"), Map.of()));
        writeSection(snapshot, 3,
                section("einheit", "Wohnung/Einheit", Map.of("Einheit", "WE 7"), Map.of()));
        writeSection(snapshot, 4, section("mda-salden-kontoauszug", "ALCO-web", Map.of(),
                Map.of("period", "01.01.2026 - 31.12.2026", "accountName", "Vorschuss")));
        writeSection(snapshot, 5, section("obj-lieferanten-detail", "ALCO-web", Map.of(),
                Map.of("supplierId", "9", "supplierName", "DEKRA Automobil GmbH")));
        writeSection(snapshot, 6, section("settlement-detail", "ALCO-web", Map.of(), Map.of(), List.of(
                        new TableData(List.of("Datum", "Buchungstext", "Betrag"), List.of(
                                List.of(cell("01.01.2026"), cell("Hausmeisterdienst Januar"), cell("100,00 EUR")),
                                List.of(cell("01.02.2026"), cell("Hausmeisterdienst Februar"), cell("100,00 EUR")))))));
        writeJson(snapshot.resolve("data/documents.json"), List.of());

        new HtmlReportWriter(snapshot).write();

        var report = Jsoup.parse(Files.readString(snapshot.resolve("contracts/0/index.html")));
        assertThat(report.select("article.section-card")).hasSize(4);
        Element masterData = report.select("article.section-card").getFirst();
        assertThat(masterData.selectFirst(".section-label").text()).isEqualTo("Stammdaten");
        assertThat(masterData.text()).contains("Max Mustermann", "DE12 3456", "WE 7")
                .doesNotContain("Zahlungsinfo", "Wohnung/Einheit");
        assertThat(masterData.select(".source-actions a")).hasSize(3);

        Element balanceStatement = report.select("article.section-card").get(1);
        assertThat(balanceStatement.selectFirst("summary").text())
                .contains("Salden-Kontoauszug", "Bezeichnung: Vorschuss");
        assertThat(balanceStatement.text()).contains("Bezeichnung Vorschuss");

        Element supplier = report.select("article.section-card").get(2);
        assertThat(supplier.selectFirst("summary").text())
                .contains("Lieferantendetail", "DEKRA Automobil GmbH")
                .doesNotContain("ALCO-web");

        Element settlementDetail = report.select("article.section-card").get(3);
        assertThat(settlementDetail.selectFirst("summary").text())
                .contains("Abrechnungsdetail", "Hausmeisterdienst Januar")
                .doesNotContain("ALCO-web", "Hausmeisterdienst Februar");
    }

    private void writeSection(Path snapshot, int sequence, SectionData section) throws Exception {
        Path relative = Path.of(section.section(), "2026", section.contractId(), "%04d.json".formatted(sequence));
        writeJson(snapshot.resolve("data/pages").resolve(relative), section);
        Path raw = snapshot.resolve("raw").resolve(relative).resolveSibling("%04d.html".formatted(sequence));
        Files.createDirectories(raw.getParent());
        Files.writeString(raw, "<html><body>Original</body></html>");
    }

    private void writeJson(Path path, Object value) throws Exception {
        Files.createDirectories(path.getParent());
        new ObjectMapper().registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .writeValue(path.toFile(), value);
    }

    private SectionData section(String section, String title, Map<String, String> fields,
                                Map<String, String> context) {
        return section(section, title, fields, context, List.of());
    }

    private SectionData section(String section, String title, Map<String, String> fields,
                                Map<String, String> context, List<TableData> tables) {
        return new SectionData(section, "0", "https://stv.alco-web.de/" + section + ".php", CAPTURED_AT,
                title, fields.values().stream().reduce((left, right) -> left + " " + right).orElse(""), context,
                fields, tables, List.of(), List.of(), List.of());
    }

    private CellData cell(String text) {
        return new CellData(text, List.of());
    }
}
