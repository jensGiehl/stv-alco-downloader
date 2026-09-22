package de.agiehl;

import static org.assertj.core.api.Assertions.assertThat;

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
                "<html><body>Backup</body></html>".getBytes(StandardCharsets.UTF_8));
        SectionData section = new PageParser(clock).parse("home", "0", raw, Map.of());

        store.writeContracts(List.of(new ContractReference("0", "71-10-1", "Contract", raw.uri())));
        store.writeSection(section, raw);
        String firstFile = store.writeAttachment("abc", "%PDF-1.7".getBytes(StandardCharsets.US_ASCII));
        String duplicateFile = store.writeAttachment("abc", "%PDF-1.7".getBytes(StandardCharsets.US_ASCII));
        store.writeDocuments(List.of());
        store.complete(clock.instant());

        assertThat(firstFile).isEqualTo(duplicateFile);
        assertThat(store.root().getFileName().toString()).isEqualTo("20260922-101530-000Z");
        assertThat(Files.readString(store.root().resolve("manifest.json"))).contains("\"status\" : \"COMPLETE\"");
        assertThat(Files.walk(store.root().resolve("raw")).filter(Files::isRegularFile).count()).isEqualTo(2);
        assertThat(Files.list(store.root().resolve("attachments"))).hasSize(1);
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
