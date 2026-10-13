package de.agiehl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SnapshotStoreTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void writesImmutableSnapshotStructureAndManifest() throws Exception {
        AlcoProperties properties = properties();
        Clock clock = Clock.fixed(Instant.parse("2026-09-22T10:15:30Z"), ZoneOffset.UTC);
        SnapshotStore store = new SnapshotStore(properties, clock);
        HttpResult raw = new HttpResult(URI.create("https://stv.alco-web.de/homeV.php"), 200, "text/html",
                "<html><body>Backup <a href=\"https://stv.alco-web.de/einheit.php\">Portal</a></body></html>"
                        .getBytes(StandardCharsets.UTF_8));
        SectionData section = new PageParser(clock).parse("home", "0", raw, Map.of());

        store.writeContracts(List.of(new ContractReference("0", "71-10-1", "Contract", raw.uri())));
        store.writeSection(section, raw);
        String firstFile = store.writeAttachment("abc", "%PDF-1.7".getBytes(StandardCharsets.US_ASCII));
        String duplicateFile = store.writeAttachment("abc", "%PDF-1.7".getBytes(StandardCharsets.US_ASCII));
        store.writeDocuments(List.of());
        store.complete(clock.instant());

        assertThat(firstFile).isEqualTo("files/documents/2026-09-22/abc.pdf");
        assertThat(duplicateFile).isEqualTo("files/documents/2026-09-22/abc_20260922_121530.pdf");
        assertThat(store.root().getFileName().toString()).isEqualTo("2026-09-22_12_15");
        assertThat(Files.readString(store.root().resolve("manifest.json"))).contains("\"status\" : \"COMPLETE\"");
        assertThat(Files.readString(store.root().resolve("index.html")))
                .contains("Ihre Daten auf einen Blick", "71-10-1");
        assertThat(Files.readString(store.root().resolve("contracts/0/index.html")))
                .contains("Contract", "Stammdaten", "Originalseite öffnen")
                .doesNotContain("href=\"https://stv.alco-web.de", "Quelle im Portal");
        assertThat(Files.readString(store.root().resolve("documents.html"))).contains("Gespeicherte Dokumente");
        assertThat(Files.readString(temporaryDirectory.resolve("index.html")))
                .contains("Ihre ALCO-Snapshots", "2026-09-22_12_15");
        assertThat(store.root().resolve("assets/bootstrap.min.css")).exists();
        assertThat(Files.walk(store.root().resolve("raw")).filter(Files::isRegularFile).count()).isEqualTo(2);
        assertThat(Files.list(store.root().resolve("files/documents/2026-09-22"))).hasSize(2);
    }

    @Test
    void createsUniqueDirectoryForEachRunWithinTheSameMinute() {
        Clock clock = Clock.fixed(Instant.parse("2026-09-22T10:15:30Z"), ZoneOffset.UTC);

        SnapshotStore firstStore = new SnapshotStore(properties(), clock);
        SnapshotStore secondStore = new SnapshotStore(properties(), clock);

        assertThat(firstStore.root().getFileName().toString()).isEqualTo("2026-09-22_12_15");
        assertThat(secondStore.root().getFileName().toString()).isEqualTo("2026-09-22_12_15_2");
        assertThat(firstStore.root()).isDirectory();
        assertThat(secondStore.root()).isDirectory();
    }

    @Test
    void countsSavedRowsAcrossTablesAndRetainsThemAfterFailure() throws Exception {
        Clock clock = Clock.fixed(Instant.parse("2026-09-22T10:15:30Z"), ZoneOffset.UTC);
        SnapshotStore store = new SnapshotStore(properties(), clock);
        HttpResult raw = new HttpResult(URI.create("https://stv.alco-web.de/kontoauszug.php"), 200, "text/html",
                """
                <html><body>
                  <table><tr><th>Buchung</th></tr><tr><td>Erste</td></tr><tr><td>Zweite</td></tr></table>
                  <table><tr><th>Saldo</th></tr><tr><td>10,00</td></tr></table>
                </body></html>
                """.getBytes(StandardCharsets.UTF_8));
        SectionData section = new PageParser(clock).parse("kontoauszug", "0", raw, Map.of());

        store.writeSection(section, raw);
        store.fail(clock.instant(), "Crawl interrupted");

        assertThat(store.pageCount()).isEqualTo(1);
        assertThat(store.recordCount()).isEqualTo(3);
        assertThat(Files.readString(store.root().resolve("manifest.json")))
                .contains("\"status\" : \"FAILED\"", "\"records\" : 3");
    }

    @Test
    void countsSavedRowsWhenWritingTheRawPageFails() throws Exception {
        Clock clock = Clock.fixed(Instant.parse("2026-09-22T10:15:30Z"), ZoneOffset.UTC);
        SnapshotStore store = new SnapshotStore(properties(), clock);
        HttpResult raw = new HttpResult(URI.create("https://stv.alco-web.de/kontoauszug.php"), 200, "text/html",
                "<html><body><table><tr><th>Buchung</th></tr><tr><td>Gesichert</td></tr></table></body></html>"
                        .getBytes(StandardCharsets.UTF_8));
        SectionData section = new PageParser(clock).parse("kontoauszug", "0", raw, Map.of());
        Files.writeString(store.root().resolve("raw/kontoauszug"), "blocked");

        assertThatThrownBy(() -> store.writeSection(section, raw)).isInstanceOf(StorageException.class);

        assertThat(store.pageCount()).isEqualTo(1);
        assertThat(store.recordCount()).isEqualTo(1);
        assertThat(store.root().resolve("data/pages/kontoauszug/2026-09-22/0/0001.json")).isRegularFile();
    }

    private AlcoProperties properties() {
        AlcoProperties properties = new AlcoProperties();
        properties.setUsername("user");
        properties.setPassword("secret");
        properties.setOutputDir(temporaryDirectory);
        properties.setPeriod(CrawlPeriod.ALL);
        properties.setRequestDelay(Duration.ZERO);
        return properties;
    }
}
