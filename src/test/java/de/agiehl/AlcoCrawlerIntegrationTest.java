package de.agiehl;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.Banner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.context.ConfigurableApplicationContext;

class AlcoCrawlerIntegrationTest {

    @TempDir
    Path outputDirectory;
    private HttpServer server;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> response(exchange,
                "<form><input name=\"password\"><h1>Bitte anmelden</h1></form>", "PHPSESSID=test; Path=/"));
        server.createContext("/index.php", exchange -> response(exchange, home(), null));
        server.createContext("/homeV.php", exchange -> response(exchange, home(), null));
        server.createContext("/vertragszahlung.php", exchange -> response(exchange, simplePage("Payment"), null));
        server.createContext("/einheit.php", exchange -> response(exchange, simplePage("Unit"), null));
        server.createContext("/infosend.php", exchange -> response(exchange, simplePage("Messages"), null));
        server.createContext("/kontoauszug.php", exchange -> response(exchange,
                "<html><body>Zeitraum: 01.01.2026 bis: 31.12.2026"
                        + "<table><tr><th>Datum</th><th>Text</th></tr><tr><td>22.09.2026</td><td>Booking</td></tr></table>"
                        + "</body></html>", null));
        server.createContext("/obj-abrechnung.php", exchange -> response(exchange,
                "<html><body><select name=\"abrechnungszeit\"><option value=\"0\" selected>"
                        + "01.01.2025 - 31.12.2025</option></select></body></html>", null));
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void createsCompleteSnapshotForStatefulWorkflow() throws Exception {
        AlcoProperties properties = new AlcoProperties();
        properties.setBaseUrl(URI.create("http://127.0.0.1:" + server.getAddress().getPort()));
        properties.setUsername("user");
        properties.setPassword("password");
        properties.setOutputDir(outputDirectory);
        properties.setPeriod(CrawlPeriod.ALL);
        properties.setRequestDelay(Duration.ZERO);
        AlcoHttpClient client = new AlcoHttpClient(properties);
        Clock clock = Clock.fixed(Instant.parse("2026-09-22T10:15:30Z"), ZoneOffset.UTC);
        AlcoCrawler crawler = new AlcoCrawler(properties, client, new PageParser(clock), clock);

        Path snapshot = crawler.crawl();

        assertThat(Files.readString(snapshot.resolve("manifest.json"))).contains("\"status\" : \"COMPLETE\"");
        assertThat(Files.walk(snapshot.resolve("data/contracts/0")).filter(Files::isRegularFile).count())
                .isGreaterThanOrEqualTo(6);
    }

    @Test
    void startsThroughSpringBootAndBindsEnvironmentStyleProperties() {
        SpringApplication application = new SpringApplication(StvAlcoDownloaderApplication.class);
        application.setBannerMode(Banner.Mode.OFF);
        application.setWebApplicationType(WebApplicationType.NONE);
        Path springOutput = outputDirectory.resolve("spring");

        ConfigurableApplicationContext context = application.run(
                "--alco.base-url=http://127.0.0.1:" + server.getAddress().getPort(),
                "--alco.username=user",
                "--alco.password=password",
                "--alco.output-dir=" + springOutput,
                "--alco.period=current-year",
                "--alco.request-delay=0ms",
                "--logging.level.root=ERROR");
        int exitCode = SpringApplication.exit(context);

        assertThat(exitCode).isZero();
        assertThat(springOutput).isDirectoryContaining(path -> path.getFileName().toString().endsWith("Z"));
    }

    private String home() {
        return """
                <html><body><div>Vertrag:</div><table>
                <tr><th>Link</th><th>Vertrag</th><th>Objekt</th></tr>
                <tr><td><a href="?aktion=anzeigen&id=0">check_box</a></td><td>71-10-1</td><td>Objekt</td></tr>
                </table></body></html>
                """;
    }

    private String simplePage(String text) {
        return "<html><body>" + text + "</body></html>";
    }

    private void response(HttpExchange exchange, String body, String cookie) throws IOException {
        if (cookie != null) {
            exchange.getResponseHeaders().add("Set-Cookie", cookie);
        }
        exchange.getResponseHeaders().add("Content-Type", "text/html; charset=UTF-8");
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
