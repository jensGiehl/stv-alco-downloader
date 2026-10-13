package de.agiehl;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
final class TelegramNotifier {

    private static final Logger LOGGER = LoggerFactory.getLogger(TelegramNotifier.class);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);

    private final TelegramProperties properties;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final URI endpoint;

    @Autowired
    TelegramNotifier(TelegramProperties properties) {
        this(properties, null);
    }

    TelegramNotifier(TelegramProperties properties, URI endpoint) {
        this.properties = properties;
        this.endpoint = endpoint;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    void notifyResult(int exitCode, CrawlProgress progress) {
        if (!properties.isConfigured()) {
            return;
        }
        try {
            URI requestEndpoint = endpoint == null
                    ? URI.create("https://api.telegram.org/bot" + properties.getBotToken().strip() + "/sendMessage")
                    : endpoint;
            String body = objectMapper.writeValueAsString(Map.of(
                    "chat_id", properties.getChatId().strip(),
                    "text", message(exitCode, progress)));
            HttpRequest request = HttpRequest.newBuilder(requestEndpoint)
                    .timeout(REQUEST_TIMEOUT)
                    .header("Content-Type", "application/json; charset=UTF-8")
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> response = httpClient.send(request,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                LOGGER.warn("Telegram notification failed with HTTP {}", response.statusCode());
                return;
            }
            JsonNode result = objectMapper.readTree(response.body());
            if (result == null || !result.path("ok").isBoolean() || !result.path("ok").booleanValue()) {
                LOGGER.warn("Telegram notification failed: unsuccessful API response");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            LOGGER.warn("Telegram notification interrupted");
        } catch (IOException | RuntimeException exception) {
            LOGGER.warn("Telegram notification failed ({})", exception.getClass().getSimpleName());
        }
    }

    private String message(int exitCode, CrawlProgress progress) {
        String text = "STV ALCO Backup\nStatus: " + (exitCode == 0 ? "erfolgreich" : "nicht erfolgreich")
                + "\nDokumente: " + progress.documentsDownloaded()
                + "\nDatensätze: " + progress.records();
        return progress.warnings() == 0 ? text : text + "\nWarnungen: " + progress.warnings();
    }
}
