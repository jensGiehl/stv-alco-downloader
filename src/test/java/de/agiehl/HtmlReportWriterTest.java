package de.agiehl;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

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
        HtmlReportWriter writer = new HtmlReportWriter(snapshot);
        writer.contracts(List.of(new ContractReference("0", "71-10-1", "Musterwohnung",
                URI.create("https://stv.alco-web.de/homeV.php?id=0"))));
        writer.section(section("home", "ALCO-web", Map.of("Eigentümer", "Max Mustermann"), Map.of()),
                "raw/home.html");
        writer.section(section("vertragszahlung", "Zahlungsinfo", Map.of("IBAN", "DE12 3456"), Map.of()),
                "raw/zahlung.html");
        writer.section(section("einheit", "Wohnung/Einheit", Map.of("Einheit", "WE 7"), Map.of()),
                "raw/einheit.html");
        writer.section(section("mda-salden-kontoauszug", "ALCO-web", Map.of(),
                        Map.of("period", "01.01.2026 - 31.12.2026", "accountName", "Vorschuss")),
                "raw/salden.html");
        writer.section(section("obj-lieferanten-detail", "ALCO-web", Map.of("Firma", "Beispiel GmbH"),
                        Map.of("supplierId", "9")),
                "raw/lieferant.html");
        writer.documents(List.of());

        writer.write(Map.of(
                "status", "COMPLETE",
                "startedAt", CAPTURED_AT,
                "period", "ALL",
                "contracts", 1,
                "pages", 5,
                "documents", 0));

        var report = Jsoup.parse(Files.readString(snapshot.resolve("contracts/0/index.html")));
        assertThat(report.select("article.section-card")).hasSize(3);
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
                .contains("Lieferantendetail", "Beispiel GmbH")
                .doesNotContain("ALCO-web");
    }

    private SectionData section(String section, String title, Map<String, String> fields,
                                Map<String, String> context) {
        return new SectionData(section, "0", "https://stv.alco-web.de/" + section + ".php", CAPTURED_AT,
                title, fields.values().stream().reduce((left, right) -> left + " " + right).orElse(""), context,
                fields, List.of(), List.of(), List.of(), List.of());
    }
}
