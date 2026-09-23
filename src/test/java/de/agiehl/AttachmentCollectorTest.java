package de.agiehl;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AttachmentCollectorTest {

    @TempDir
    Path outputDirectory;
    private HttpServer server;
    private AlcoProperties properties;
    private AlcoHttpClient client;
    private final AtomicInteger pdfRequests = new AtomicInteger();

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> html(exchange,
                "<form><input name=\"password\"><h1>Bitte anmelden</h1></form>", "PHPSESSID=test; Path=/"));
        server.createContext("/index.php", exchange -> html(exchange,
                "<a href=\"homeV.php\">Home</a><div>Vertrag:</div>", null));
        server.createContext("/showpdf.php", exchange -> {
            pdfRequests.incrementAndGet();
            byte[] bytes = "%PDF-1.7\nexample".getBytes(StandardCharsets.US_ASCII);
            exchange.getResponseHeaders().add("Content-Type", "application/pdf");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.createContext("/invalid.pdf", exchange -> html(exchange, "<html>Login</html>", null));
        server.createContext("/missing.pdf", exchange -> {
            exchange.sendResponseHeaders(404, -1);
            exchange.close();
        });
        server.start();
        properties = new AlcoProperties();
        properties.setBaseUrl(URI.create("http://127.0.0.1:" + server.getAddress().getPort()));
        properties.setUsername("user");
        properties.setPassword("password");
        properties.setOutputDir(outputDirectory);
        properties.setRequestDelay(Duration.ZERO);
        client = new AlcoHttpClient(properties);
        client.authenticate();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void downloadsDuplicateDocumentIdOnlyOnce() {
        AttachmentCollector collector = new AttachmentCollector();
        String href = client.resolve("/showpdf.php?ID=42").toString();
        collector.register(section(List.of(
                new DiscoveredDocument("42", "First", href, "home", "source-1", "0"),
                new DiscoveredDocument("42", "Second", href, "payment", "source-2", "0"))));
        SnapshotStore store = store();

        List<DocumentData> documents = collector.downloadAll(client, store);

        assertThat(pdfRequests).hasValue(1);
        assertThat(documents).singleElement().satisfies(document -> {
            assertThat(document.sources()).hasSize(2);
            assertThat(document.sha256()).hasSize(64);
        });
    }

    @Test
    void skipsNonPdfAndFailedDownloadsAndContinuesWithRemainingDocuments() {
        AttachmentCollector collector = new AttachmentCollector();
        String validHref = client.resolve("/showpdf.php?ID=43").toString();
        collector.register(section(List.of(
                new DiscoveredDocument("invalid", "Invalid", client.resolve("/invalid.pdf").toString(), "home",
                        "source", "0"),
                new DiscoveredDocument("missing", "Missing", client.resolve("/missing.pdf").toString(), "home",
                        "source", "0"),
                new DiscoveredDocument("43", "Valid", validHref, "home", "source", "0"))));
        SnapshotStore store = store();

        List<DocumentData> documents = collector.downloadAll(client, store);

        assertThat(documents).singleElement().extracting(DocumentData::id).isEqualTo("43");
        assertThat(pdfRequests).hasValue(1);
        assertThat(store.warningCount()).isEqualTo(2);
    }

    private SectionData section(List<DiscoveredDocument> documents) {
        return new SectionData("home", "0", "source", Instant.now(), "", "", Map.of(), Map.of(),
                List.of(), List.of(), List.of(), documents);
    }

    private SnapshotStore store() {
        return new SnapshotStore(properties,
                Clock.fixed(Instant.parse("2026-09-22T10:15:30Z"), ZoneOffset.UTC));
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
