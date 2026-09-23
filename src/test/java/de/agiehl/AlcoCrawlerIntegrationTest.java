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
import java.util.concurrent.atomic.AtomicInteger;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.Banner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.ConfigurableApplicationContext;

@ExtendWith(OutputCaptureExtension.class)
class AlcoCrawlerIntegrationTest {

    @TempDir
    Path outputDirectory;
    private HttpServer server;
    private final AtomicInteger accountRequests = new AtomicInteger();

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
        server.createContext("/kontoauszug.php", exchange -> {
            if (exchange.getRequestURI().getRawQuery() != null
                    && exchange.getRequestURI().getRawQuery().contains("KTNTYP=")) {
                response(exchange, "<html><body><table><tr><th>Datum</th><th>Buchungstext</th></tr>"
                        + "<tr><td>01.02.2026</td><td>Detail</td></tr></table></body></html>", null);
                return;
            }
            int year = 2025 + accountRequests.incrementAndGet();
            response(exchange,
                    "<html><body>Zeitraum: 01.01." + year + " bis: 31.12." + year
                        + "<a href=\"?AUFRUFTYP=V&ID=vor\">vor</a>"
                        + "<table><tr><th>Datum</th><th>Text</th></tr><tr><td>22.09.2026</td><td>Booking</td></tr></table>"
                        + "</body></html>", null);
        });
        server.createContext("/mda-salden.php", exchange -> response(exchange,
                "<html><body>Angaben für den Zeitraum: 01.01.2026 - 31.12.2026"
                        + "<table><tr><th>Bezeichnung</th><th>Saldo</th></tr><tr><td>"
                        + "<a href=\"kontoauszug.php?ID=11&amp;NAME=Wartung BHKW 70%&amp;KTNTYP=GV\">Konto</a>"
                        + "</td><td>1,00</td></tr></table>"
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
        Path pagesDirectory = snapshot.resolve("data/pages");
        assertThat(Files.walk(pagesDirectory).filter(Files::isRegularFile).count())
                .isGreaterThanOrEqualTo(8);
        try (var files = Files.list(pagesDirectory.resolve("mda-salden/2026/0"))) {
            Path balances = files.findFirst().orElseThrow();
            assertThat(Files.readString(balances)).contains("Wartung%20BHKW%2070%25");
        }
        assertThat(accountRequests).hasValue(1);
    }

    @Test
    void startsThroughSpringBootAndLogsFinalSummary(CapturedOutput output) {
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
        assertThat(springOutput).isDirectoryContaining(path -> path.getFileName().toString()
                .matches("\\d{4}-\\d{2}-\\d{2}_\\d{2}_\\d{2}(?:_\\d+)?"));
        assertThat(output).contains("ALCO backup finished: status=SUCCESS")
                .contains("contractsCompleted=1, contractsDiscovered=1, pages=8")
                .contains("documentsDownloaded=0, documentsDiscovered=0, attachmentBytes=0, warnings=0");
    }

    @Test
    void logsFinalSummaryWhenConfigurationIsInvalid(CapturedOutput output) {
        SpringApplication application = new SpringApplication(StvAlcoDownloaderApplication.class);
        application.setBannerMode(Banner.Mode.OFF);
        application.setWebApplicationType(WebApplicationType.NONE);

        ConfigurableApplicationContext context = application.run(
                "--alco.username=",
                "--alco.password=password",
                "--logging.level.root=ERROR");
        int exitCode = SpringApplication.exit(context);

        assertThat(exitCode).isEqualTo(2);
        assertThat(output).contains("ALCO backup finished: status=FAILED")
                .contains("attempts=0, sessionRestarts=0")
                .contains("snapshot=-");
    }

    private String home() {
        return """
                <html><body><div>Vertrag:</div><table>
                <tr><th>Link</th><th>Vertrag</th><th>Objekt</th></tr>
                <tr><td><a href="?aktion=anzeigen&id=0">check_box</a></td><td>71-10-1</td><td>Objekt</td></tr>
                </table><a href="mda-salden.php">Salden</a></body></html>
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
