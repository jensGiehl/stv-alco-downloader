package de.agiehl;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;

@Tag("live")
@EnabledIfEnvironmentVariable(named = "ALCO_USERNAME", matches = ".+")
@EnabledIfEnvironmentVariable(named = "ALCO_PASSWORD", matches = ".+")
class LiveSmokeTest {

    @TempDir
    Path outputDirectory;

    @Test
    void createsReadOnlyCurrentYearBackup() throws Exception {
        AlcoProperties properties = new AlcoProperties();
        properties.setBaseUrl(URI.create(System.getenv().getOrDefault("ALCO_BASE_URL",
                "https://stv.alco-web.de")));
        properties.setUsername(System.getenv("ALCO_USERNAME"));
        properties.setPassword(System.getenv("ALCO_PASSWORD"));
        properties.setOutputDir(outputDirectory);
        properties.setPeriod(CrawlPeriod.CURRENT_YEAR);
        properties.setRequestDelay(Duration.ofMillis(500));
        AlcoHttpClient client = new AlcoHttpClient(properties);
        AlcoCrawler crawler = new AlcoCrawler(properties, client, new PageParser());

        Path snapshot = crawler.crawl();

        assertThat(Files.readString(snapshot.resolve("manifest.json"))).contains("\"status\" : \"COMPLETE\"");
    }
}
