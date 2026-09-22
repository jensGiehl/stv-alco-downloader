package de.agiehl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AlcoHttpClientTest {

    private HttpServer server;
    private AlcoProperties properties;
    private final AtomicReference<String> loginBody = new AtomicReference<>();
    private final AtomicReference<String> receivedCookie = new AtomicReference<>();

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> respond(exchange, 200,
                "<form><input name=\"password\"><h1>Bitte anmelden</h1></form>",
                "PHPSESSID=test-session; Path=/"));
        server.createContext("/index.php", exchange -> {
            loginBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            respond(exchange, 200, "<html><a href=\"homeV.php\">Home</a><div>Vertrag:</div></html>", null);
        });
        server.createContext("/homeV.php", exchange -> {
            receivedCookie.set(exchange.getRequestHeaders().getFirst("Cookie"));
            respond(exchange, 200, "<html><body>Authenticated</body></html>", null);
        });
        server.start();
        properties = new AlcoProperties();
        properties.setBaseUrl(URI.create("http://127.0.0.1:" + server.getAddress().getPort()));
        properties.setUsername("test-user");
        properties.setPassword("test-password");
        properties.setRequestDelay(Duration.ZERO);
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void authenticatesWithCookieAndKeepsCredentialsOutOfUris() {
        AlcoHttpClient client = new AlcoHttpClient(properties);

        client.authenticate();
        HttpResult result = client.get(client.resolve("/homeV.php"));

        assertThat(result.bodyAsString()).contains("Authenticated");
        assertThat(loginBody.get()).contains("username=test-user", "password=test-password");
        assertThat(receivedCookie.get()).contains("PHPSESSID=test-session");
        assertThat(result.uri().toString()).doesNotContain("test-user", "test-password");
    }

    @Test
    void refusesMutatingAndForeignEndpoints() {
        AlcoHttpClient client = new AlcoHttpClient(properties);

        assertThatThrownBy(() -> client.get(client.resolve("/writeinfo.php")))
                .isInstanceOf(CrawlerException.class)
                .hasMessageContaining("mutating endpoint");
        assertThatThrownBy(() -> client.get(URI.create("https://example.org/")))
                .isInstanceOf(CrawlerException.class)
                .hasMessageContaining("outside");
    }

    private void respond(HttpExchange exchange, int status, String body, String cookie) throws IOException {
        if (cookie != null) {
            exchange.getResponseHeaders().add("Set-Cookie", cookie);
        }
        exchange.getResponseHeaders().add("Content-Type", "text/html; charset=UTF-8");
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
