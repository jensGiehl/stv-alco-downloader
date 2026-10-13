package de.agiehl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

@ExtendWith(OutputCaptureExtension.class)
class TelegramNotifierTest {

    private static final String BOT_TOKEN = "123456:secret-bot-token";
    private static final String CHAT_ID = "-987654321";

    private final AtomicInteger requests = new AtomicInteger();
    private final AtomicReference<String> requestMethod = new AtomicReference<>();
    private final AtomicReference<String> requestContentType = new AtomicReference<>();
    private final AtomicReference<String> requestBody = new AtomicReference<>();
    private final AtomicInteger responseStatus = new AtomicInteger(200);
    private final AtomicReference<String> responseBody = new AtomicReference<>("{\"ok\":true}");
    private HttpServer server;
    private URI endpoint;
    private TelegramProperties properties;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/bot" + BOT_TOKEN + "/sendMessage", this::respond);
        server.start();
        endpoint = URI.create("http://127.0.0.1:" + server.getAddress().getPort()
                + "/bot" + BOT_TOKEN + "/sendMessage");
        properties = new TelegramProperties();
        properties.setBotToken(BOT_TOKEN);
        properties.setChatId(CHAT_ID);
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void leavesNotificationDisabledByDefault() {
        TelegramProperties defaults = new TelegramProperties();

        new TelegramNotifier(defaults, endpoint).notifyResult(0, new CrawlProgress());

        assertThat(defaults.isConfigured()).isFalse();
        assertThat(requests.get()).isZero();
    }

    @ParameterizedTest
    @MethodSource("incompleteConfigurations")
    void skipsRequestsForIncompleteConfiguration(String botToken, String chatId) {
        properties.setBotToken(botToken);
        properties.setChatId(chatId);

        new TelegramNotifier(properties, endpoint).notifyResult(0, new CrawlProgress());

        assertThat(properties.isConfigured()).isFalse();
        assertThat(requests.get()).isZero();
    }

    @ParameterizedTest
    @MethodSource("crawlResults")
    void sendsFinalStatusAndCountsAsUtf8Json(int exitCode, String expectedStatus) throws IOException {
        CrawlProgress progress = new CrawlProgress();
        progress.documentsDownloaded(7, 1024);
        progress.records(4_294_967_296L);
        progress.warnings(2);

        new TelegramNotifier(properties, endpoint).notifyResult(exitCode, progress);

        assertThat(requests.get()).isEqualTo(1);
        assertThat(requestMethod.get()).isEqualTo("POST");
        assertThat(requestContentType.get()).isEqualTo("application/json; charset=UTF-8");
        JsonNode payload = new ObjectMapper().readTree(requestBody.get());
        assertThat(payload.path("chat_id").asText()).isEqualTo(CHAT_ID);
        assertThat(payload.path("text").asText()).isEqualTo("STV ALCO Backup\nStatus: " + expectedStatus
                + "\nDokumente: 7\nDatensätze: 4294967296\nWarnungen: 2");
    }

    @Test
    void reportsZeroCountsWithoutWarnings() throws IOException {
        new TelegramNotifier(properties, endpoint).notifyResult(3, new CrawlProgress());

        JsonNode payload = new ObjectMapper().readTree(requestBody.get());
        assertThat(payload.path("text").asText()).isEqualTo(
                "STV ALCO Backup\nStatus: nicht erfolgreich\nDokumente: 0\nDatensätze: 0");
    }

    @ParameterizedTest
    @MethodSource("unsuccessfulResponses")
    void handlesFailedResponsesWithoutLeakingSecrets(int status, String body, CapturedOutput output) {
        responseStatus.set(status);
        responseBody.set(body);

        assertThatCode(() -> new TelegramNotifier(properties, endpoint).notifyResult(0, new CrawlProgress()))
                .doesNotThrowAnyException();

        assertThat(requests.get()).isEqualTo(1);
        assertThat(output.getOut()).contains("Telegram notification failed")
                .doesNotContain(BOT_TOKEN, CHAT_ID, endpoint.toString(), "private-response-body");
    }

    @Test
    void handlesNetworkFailureWithoutLeakingSecrets(CapturedOutput output) {
        server.stop(0);

        assertThatCode(() -> new TelegramNotifier(properties, endpoint).notifyResult(0, new CrawlProgress()))
                .doesNotThrowAnyException();

        assertThat(output.getOut()).contains("Telegram notification failed")
                .doesNotContain(BOT_TOKEN, CHAT_ID, endpoint.toString());
    }

    @Test
    void handlesInvalidTokenWithoutLeakingSecrets(CapturedOutput output) {
        properties.setBotToken("private token with spaces");

        assertThatCode(() -> new TelegramNotifier(properties).notifyResult(0, new CrawlProgress()))
                .doesNotThrowAnyException();

        assertThat(output.getOut()).contains("Telegram notification failed")
                .doesNotContain("private token with spaces", CHAT_ID);
        assertThat(requests.get()).isZero();
    }

    @Test
    void preservesInterruptedStatus(CapturedOutput output) {
        TelegramNotifier notifier = new TelegramNotifier(properties, endpoint);

        Thread.currentThread().interrupt();
        try {
            assertThatCode(() -> notifier.notifyResult(0, new CrawlProgress())).doesNotThrowAnyException();
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }

        assertThat(output.getOut()).contains("Telegram notification interrupted")
                .doesNotContain(BOT_TOKEN, CHAT_ID, endpoint.toString());
    }

    private static Stream<Arguments> incompleteConfigurations() {
        return Stream.of(
                Arguments.of(null, CHAT_ID),
                Arguments.of("", CHAT_ID),
                Arguments.of(" \t\n", CHAT_ID),
                Arguments.of(BOT_TOKEN, null),
                Arguments.of(BOT_TOKEN, ""),
                Arguments.of(BOT_TOKEN, " \t\n"),
                Arguments.of(null, null));
    }

    private static Stream<Arguments> crawlResults() {
        return Stream.of(
                Arguments.of(0, "erfolgreich"),
                Arguments.of(1, "nicht erfolgreich"),
                Arguments.of(2, "nicht erfolgreich"),
                Arguments.of(3, "nicht erfolgreich"));
    }

    private static Stream<Arguments> unsuccessfulResponses() {
        return Stream.of(
                Arguments.of(503, "{\"ok\":false,\"description\":\"private-response-body\"}"),
                Arguments.of(302, "private-response-body"),
                Arguments.of(200, "{\"ok\":false,\"description\":\"private-response-body\"}"),
                Arguments.of(200, "{\"ok\":\"true\"}"),
                Arguments.of(200, "{}"),
                Arguments.of(200, "null"),
                Arguments.of(200, "private-response-body"));
    }

    private void respond(HttpExchange exchange) throws IOException {
        requests.incrementAndGet();
        requestMethod.set(exchange.getRequestMethod());
        requestContentType.set(exchange.getRequestHeaders().getFirst("Content-Type"));
        requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
        exchange.getResponseHeaders().set("Location", endpoint.toString());
        byte[] bytes = responseBody.get().getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(responseStatus.get(), bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
