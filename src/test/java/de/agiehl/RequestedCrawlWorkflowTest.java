package de.agiehl;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RequestedCrawlWorkflowTest {

    @TempDir
    Path outputDirectory;
    private HttpServer server;
    private final AtomicInteger activeContract = new AtomicInteger();
    private final Map<Integer, AtomicInteger> accountPositions = new ConcurrentHashMap<>();
    private final AtomicInteger staticPageRequests = new AtomicInteger();
    private final AtomicInteger accountRequests = new AtomicInteger();
    private final AtomicInteger balanceOverviewRequests = new AtomicInteger();
    private final AtomicInteger balanceDetailRequests = new AtomicInteger();
    private final AtomicInteger balancePosition = new AtomicInteger();

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> html(exchange,
                "<form><input name=\"password\"><h1>Bitte anmelden</h1></form>", "PHPSESSID=test; Path=/"));
        server.createContext("/index.php", exchange -> html(exchange, home(), null));
        server.createContext("/homeV.php", exchange -> {
            String id = query(exchange).get("id");
            if (id != null && id.matches("\\d+")) {
                activeContract.set(Integer.parseInt(id));
            }
            html(exchange, home(), null);
        });
        server.createContext("/vertragszahlung.php", exchange -> staticPage(exchange, "Zahlungsdaten"));
        server.createContext("/einheit.php", exchange -> staticPage(exchange, "Einheit"));
        server.createContext("/infosend.php", exchange -> staticPage(exchange, "Mitteilungen"));
        server.createContext("/mda_objekte.php", exchange -> html(exchange,
                "<html><body><a href=\"showpdf.php?ID=501\">Objektplan</a></body></html>", null));
        server.createContext("/beschluss.php", exchange -> html(exchange,
                "<html><body><p>Beschluss Fassade</p><div>Sanierung beschlossen</div></body></html>", null));
        server.createContext("/obj-lieferanten.php", this::supplierPage);
        server.createContext("/mda-salden.php", this::balancePage);
        server.createContext("/kontoauszug.php", this::accountPage);
        server.createContext("/obj-abrechnung.php", exchange -> html(exchange, "<html><body>Keine Abrechnung</body></html>", null));
        server.createContext("/showpdf.php", this::pdf);
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void crawlsAccountStatementsForEveryContractAndEverythingElseOnlyForTheFirst() throws Exception {
        AlcoProperties properties = new AlcoProperties();
        properties.setBaseUrl(URI.create("http://127.0.0.1:" + server.getAddress().getPort()));
        properties.setUsername("user");
        properties.setPassword("password");
        properties.setOutputDir(outputDirectory);
        properties.setPeriod(CrawlPeriod.ALL);
        properties.setRequestDelay(Duration.ZERO);
        Clock clock = Clock.fixed(Instant.parse("2026-09-23T10:15:30Z"), ZoneOffset.UTC);
        AlcoCrawler crawler = new AlcoCrawler(properties, new AlcoHttpClient(properties), new PageParser(clock), clock);

        Path snapshot = crawler.crawl();

        assertThat(staticPageRequests).hasValue(2);
        assertThat(accountRequests).hasValue(6);
        assertThat(balanceOverviewRequests).hasValue(4);
        assertThat(balanceDetailRequests).hasValue(3);
        assertThat(snapshot.resolve("data/pages/kontoauszug/2026/0")).isDirectory();
        assertThat(snapshot.resolve("data/pages/kontoauszug/2025/0")).isDirectory();
        assertThat(snapshot.resolve("data/pages/kontoauszug/2026/1")).isDirectory();
        assertThat(snapshot.resolve("data/pages/kontoauszug/2025/1")).isDirectory();
        assertThat(snapshot.resolve("data/pages/kontoauszug/2024")).doesNotExist();
        assertThat(snapshot.resolve("data/pages/vertragszahlung/2026-09-23/0")).isDirectory();
        assertThat(snapshot.resolve("data/pages/vertragszahlung/2026-09-23/1")).doesNotExist();
        assertThat(snapshot.resolve("data/pages/mda-salden/2023/0")).isDirectory();
        assertThat(snapshot.resolve("data/pages/mda-salden/2022/0")).isDirectory();
        assertThat(snapshot.resolve("data/pages/mda-salden/2021/0")).isDirectory();
        assertThat(snapshot.resolve("files/mda-objekte/2026-09-23/501.pdf")).exists();
        assertThat(snapshot.resolve("files/mda-salden-kontoauszug/2023/2023.pdf")).exists();

        Path balanceDetail;
        try (var files = Files.list(snapshot.resolve("data/pages/mda-salden-kontoauszug/2023/0"))) {
            balanceDetail = files.findFirst().orElseThrow();
        }
        assertThat(Files.readString(balanceDetail))
                .contains("\"accountName\" : \"Vorschuss 2023\"")
                .contains("\"storedFile\" : \"files/mda-salden-kontoauszug/2023/2023.pdf\"");
        assertThat(Files.readString(snapshot.resolve("contracts/0/index.html")))
                .contains("../../files/mda-salden-kontoauszug/2023/2023.pdf")
                .doesNotContain("href=\"http://", "href=\"https://", "Quelle im Portal");

        Path resolution;
        try (var files = Files.list(snapshot.resolve("data/pages/beschluss/2026-09-23/0"))) {
            resolution = files.findFirst().orElseThrow();
        }
        assertThat(Files.readString(resolution)).contains("Beschluss Fassade", "Sanierung beschlossen");
        assertThat(Files.walk(snapshot.resolve("data/pages/obj-lieferanten-detail"))
                .filter(Files::isRegularFile).count()).isEqualTo(2);
        assertThat(snapshot.resolve("data/pages/infosend")).doesNotExist();
    }

    private void staticPage(HttpExchange exchange, String text) throws IOException {
        staticPageRequests.incrementAndGet();
        html(exchange, "<html><body>" + text + "</body></html>", null);
    }

    private void supplierPage(HttpExchange exchange) throws IOException {
        String id = query(exchange).get("id");
        if (id == null) {
            html(exchange, """
                    <html><body><table><tr><th>Lieferant</th></tr>
                    <tr><td><a href="obj-lieferanten.php?aktion=anzeigen&amp;id=1">A</a></td></tr>
                    <tr><td><a href="obj-lieferanten.php?aktion=anzeigen&amp;id=2">B</a></td></tr>
                    </table></body></html>
                    """, null);
            return;
        }
        html(exchange, "<html><body><dl><dt>Firma:</dt><dd>Lieferant " + id
                + "</dd><dt>Telefon:</dt><dd>123" + id + "</dd></dl></body></html>", null);
    }

    private void balancePage(HttpExchange exchange) throws IOException {
        balanceOverviewRequests.incrementAndGet();
        if ("zurueck".equals(query(exchange).get("id"))) {
            balancePosition.updateAndGet(value -> Math.min(2, value + 1));
        } else {
            balancePosition.set(0);
        }
        int year = 2023 - balancePosition.get();
        html(exchange, "<html><body>Angaben für den Zeitraum: 01.01." + year + " - 31.12." + year
                + "<a href=\"mda-salden.php?aktion=anzeigen&amp;id=zurueck\">zurueck</a>"
                + "<table><tr><th>Bezeichnung</th><th>Saldo</th></tr><tr><td>"
                + "<a href=\"kontoauszug.php?ID=" + year + "&amp;NAME=Vorschuss " + year
                + "&amp;KTNTYP=GE\">Vorschuss " + year + "</a></td><td>10,00</td></tr></table>"
                + "</body></html>", null);
    }

    private void accountPage(HttpExchange exchange) throws IOException {
        Map<String, String> query = query(exchange);
        if (query.containsKey("KTNTYP")) {
            balanceDetailRequests.incrementAndGet();
            String id = query.get("ID");
            html(exchange, "<html><body><table><tr><th>Datum</th><th>Buchungstext</th></tr>"
                    + "<tr><td>01.02." + id + "</td><td><a href=\"showpdf.php?ID=" + id
                    + "\">Beleg</a></td></tr></table></body></html>", null);
            return;
        }
        accountRequests.incrementAndGet();
        AtomicInteger position = accountPositions.computeIfAbsent(activeContract.get(), ignored -> new AtomicInteger());
        if (query.containsKey("AUFRUFTYP")) {
            position.set(0);
        } else if ("zurueck".equals(query.get("id"))) {
            position.incrementAndGet();
        }
        int year = 2026 - position.get();
        String rows = position.get() >= 2 ? "" : "<tr><td>01.01." + year + "</td><td>Buchung Vertrag "
                + activeContract.get() + "</td></tr>";
        html(exchange, "<html><body><h2>Ihre Buchungsübersicht</h2>Zeitraum: 01.01." + year
                + " bis: 31.12." + year
                + "<a href=\"kontoauszug.php?aktion=anzeigen&amp;id=zurueck\">zurueck</a>"
                + "<table><tr><th>Datum</th><th>Buchungstext</th></tr>" + rows + "</table></body></html>", null);
    }

    private String home() {
        return """
                <html><body><table><tr><th>Link</th><th>Vertrag</th><th>Objekt</th></tr>
                <tr><td><a href="homeV.php?aktion=anzeigen&amp;id=0">A</a></td><td>71-10-1</td><td>Objekt A</td></tr>
                <tr><td><a href="homeV.php?aktion=anzeigen&amp;id=1">B</a></td><td>71-20-1</td><td>Objekt B</td></tr>
                </table><a href="mda_objekte.php">Objekte</a><a href="beschluss.php">Beschlüsse</a>
                <a href="obj-lieferanten.php">Lieferanten</a><a href="mda-salden.php">Salden</a></body></html>
                """;
    }

    private Map<String, String> query(HttpExchange exchange) {
        Map<String, String> values = new LinkedHashMap<>();
        String raw = exchange.getRequestURI().getRawQuery();
        if (raw == null) {
            return values;
        }
        for (String pair : raw.split("&")) {
            String[] parts = pair.split("=", 2);
            values.put(URLDecoder.decode(parts[0], StandardCharsets.UTF_8),
                    parts.length == 2 ? URLDecoder.decode(parts[1], StandardCharsets.UTF_8) : "");
        }
        return values;
    }

    private void pdf(HttpExchange exchange) throws IOException {
        byte[] bytes = "%PDF-1.7\ntest".getBytes(StandardCharsets.US_ASCII);
        exchange.getResponseHeaders().add("Content-Type", "application/pdf");
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private void html(HttpExchange exchange, String body, String cookie) throws IOException {
        if (cookie != null) {
            exchange.getResponseHeaders().add("Set-Cookie", cookie);
        }
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "text/html; charset=UTF-8");
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
