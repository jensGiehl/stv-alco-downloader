package de.agiehl;

import java.io.IOException;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
final class AlcoHttpClient {

    private static final Logger LOGGER = LoggerFactory.getLogger(AlcoHttpClient.class);
    private static final Set<String> ALLOWED_POST_PATHS = Set.of("/index.php", "/obj-abrechnung.php");
    private static final Set<String> FORBIDDEN_PATHS = Set.of("/writeinfo.php", "/sd-safe.php", "/changepw.php",
            "/changeun.php", "/admcpw.php", "/logout.php", "/pdf.php");

    private final AlcoProperties properties;
    private final HttpClient httpClient;
    private long lastRequestNanos;

    AlcoHttpClient(AlcoProperties properties) {
        this.properties = properties;
        CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        this.httpClient = HttpClient.newBuilder()
                .cookieHandler(cookies)
                .connectTimeout(Duration.ofSeconds(30))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    synchronized void authenticate() {
        URI loginPage = resolve("/");
        execute(HttpRequest.newBuilder(loginPage).GET().build(), true);
        Map<String, String> credentials = Map.of(
                "username", properties.getUsername(),
                "password", properties.getPassword());
        HttpResult result = execute(formRequest(resolve("/index.php"), credentials), true);
        String html = result.bodyAsString();
        if (isLoginPage(html) || !(html.contains("homeV.php") || html.contains("Vertrag:"))) {
            throw new AuthenticationException("Login failed");
        }
    }

    synchronized HttpResult get(URI uri) {
        validateUri(uri, false);
        HttpResult result = execute(HttpRequest.newBuilder(uri).GET().build(), false);
        if (isLoginPage(result.bodyAsString())) {
            authenticate();
            throw new SessionExpiredException("Session expired; the crawl must restart");
        }
        return result;
    }

    synchronized HttpResult postForm(URI uri, Map<String, String> form) {
        validateUri(uri, true);
        HttpResult result = execute(formRequest(uri, form), false);
        if (isLoginPage(result.bodyAsString())) {
            authenticate();
            throw new SessionExpiredException("Session expired; the crawl must restart");
        }
        return result;
    }

    URI resolve(String path) {
        String base = properties.getBaseUrl().toString();
        URI normalizedBase = URI.create(base.endsWith("/") ? base : base + "/");
        return normalizedBase.resolve(path.startsWith("/") ? path.substring(1) : path);
    }

    private HttpRequest formRequest(URI uri, Map<String, String> form) {
        String body = form.entrySet().stream()
                .map(entry -> encode(entry.getKey()) + "=" + encode(entry.getValue()))
                .collect(Collectors.joining("&"));
        return HttpRequest.newBuilder(uri)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
    }

    private HttpResult execute(HttpRequest originalRequest, boolean authenticationRequest) {
        validateUri(originalRequest.uri(), "POST".equalsIgnoreCase(originalRequest.method()));
        RuntimeException lastFailure = null;
        for (int attempt = 1; attempt <= 3; attempt++) {
            awaitRateLimit();
            long startedNanos = System.nanoTime();
            LOGGER.debug("Sending HTTP {} request to {} (attempt {}/3)", originalRequest.method(),
                    originalRequest.uri().getPath(), attempt);
            try {
                HttpRequest request = cloneRequest(originalRequest);
                HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
                String contentType = response.headers().firstValue("Content-Type").orElse("");
                HttpResult result = new HttpResult(response.uri(), response.statusCode(), contentType, response.body());
                LOGGER.debug("Received HTTP {} from {} in {} ms ({} bytes)", response.statusCode(),
                        response.uri().getPath(), elapsedMillis(startedNanos), response.body().length);
                if (response.statusCode() == 429 || response.statusCode() >= 500) {
                    lastFailure = new CrawlerException("Remote service temporarily failed with HTTP "
                            + response.statusCode() + " on " + response.uri().getPath());
                    handleRetry(attempt, "HTTP " + response.statusCode(), response.uri().getPath());
                    continue;
                }
                if (response.statusCode() < 200 || response.statusCode() >= 400) {
                    throw new CrawlerException("Unexpected HTTP " + response.statusCode() + " on "
                            + response.uri().getPath());
                }
                if (!authenticationRequest && isLoginPage(result.bodyAsString())) {
                    return result;
                }
                return result;
            } catch (IOException exception) {
                lastFailure = new CrawlerException("Network request failed on " + originalRequest.uri().getPath(),
                        exception);
                handleRetry(attempt, "Network request failed", originalRequest.uri().getPath());
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new CrawlerException("Network request was interrupted", exception);
            }
        }
        throw lastFailure == null ? new CrawlerException("Network request failed") : lastFailure;
    }

    private HttpRequest cloneRequest(HttpRequest request) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(request.uri())
                .timeout(Duration.ofSeconds(90))
                .header("User-Agent", "stv-alco-downloader/1.0")
                .method(request.method(), request.bodyPublisher().orElse(HttpRequest.BodyPublishers.noBody()));
        request.headers().map().forEach((name, values) -> values.forEach(value -> builder.header(name, value)));
        return builder.build();
    }

    private void validateUri(URI uri, boolean post) {
        URI base = properties.getBaseUrl();
        int defaultPort = "https".equalsIgnoreCase(base.getScheme()) ? 443 : 80;
        int basePort = base.getPort() == -1 ? defaultPort : base.getPort();
        int uriPort = uri.getPort() == -1 ? defaultPort : uri.getPort();
        if (!base.getScheme().equalsIgnoreCase(uri.getScheme()) || !base.getHost().equalsIgnoreCase(uri.getHost())
                || basePort != uriPort) {
            throw new CrawlerException("Refusing request outside the configured ALCO origin");
        }
        String path = uri.getPath().toLowerCase(Locale.ROOT);
        String endpoint = path.substring(path.lastIndexOf('/'));
        if (FORBIDDEN_PATHS.contains(endpoint)) {
            throw new CrawlerException("Refusing access to a mutating endpoint: " + endpoint);
        }
        if (post && !ALLOWED_POST_PATHS.contains(endpoint)) {
            throw new CrawlerException("Refusing POST request to a non-approved endpoint: " + endpoint);
        }
    }

    private void awaitRateLimit() {
        long delayNanos = properties.getRequestDelay().toNanos();
        long elapsed = System.nanoTime() - lastRequestNanos;
        if (lastRequestNanos != 0 && elapsed < delayNanos) {
            sleep(Duration.ofNanos(delayNanos - elapsed));
        }
        lastRequestNanos = System.nanoTime();
    }

    private void backoff(int attempt) {
        sleep(Duration.ofSeconds(1L << Math.max(0, attempt - 1)));
    }

    private void handleRetry(int attempt, String failure, String path) {
        if (attempt < 3) {
            LOGGER.warn("{} on {}; retrying after backoff (attempt {}/3)", failure, path, attempt);
            backoff(attempt);
        } else {
            LOGGER.warn("{} on {}; no retry attempts remaining", failure, path);
        }
    }

    private void sleep(Duration duration) {
        try {
            Thread.sleep(duration);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new CrawlerException("Crawler delay was interrupted", exception);
        }
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private long elapsedMillis(long startedNanos) {
        return (System.nanoTime() - startedNanos) / 1_000_000;
    }

    private static boolean isLoginPage(String html) {
        return html.contains("name=\"password\"") && html.contains("Bitte anmelden");
    }
}
