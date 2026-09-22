package de.agiehl;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "alco")
public class AlcoProperties {

    private URI baseUrl = URI.create("https://stv.alco-web.de");
    private String username = "";
    private String password = "";
    private Path outputDir = Path.of("./backups");
    private CrawlPeriod period = CrawlPeriod.ALL;
    private Duration requestDelay = Duration.ofMillis(500);

    public void validate() {
        if (username == null || username.isBlank()) {
            throw new ConfigurationException("ALCO_USERNAME is required");
        }
        if (password == null || password.isBlank()) {
            throw new ConfigurationException("ALCO_PASSWORD is required");
        }
        if (baseUrl == null || baseUrl.getHost() == null || !isSecureOrLoopback(baseUrl)) {
            throw new ConfigurationException("ALCO_BASE_URL must be an HTTPS URL");
        }
        if (outputDir == null) {
            throw new ConfigurationException("ALCO_OUTPUT_DIR is required");
        }
        if (period == null) {
            throw new ConfigurationException("ALCO_PERIOD is required");
        }
        if (requestDelay == null || requestDelay.isNegative()) {
            throw new ConfigurationException("ALCO_REQUEST_DELAY must not be negative");
        }
    }

    public URI getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(URI baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public Path getOutputDir() {
        return outputDir;
    }

    public void setOutputDir(Path outputDir) {
        this.outputDir = outputDir;
    }

    public CrawlPeriod getPeriod() {
        return period;
    }

    public void setPeriod(CrawlPeriod period) {
        this.period = period;
    }

    public Duration getRequestDelay() {
        return requestDelay;
    }

    public void setRequestDelay(Duration requestDelay) {
        this.requestDelay = requestDelay;
    }

    private boolean isSecureOrLoopback(URI uri) {
        if ("https".equalsIgnoreCase(uri.getScheme())) {
            return true;
        }
        String host = uri.getHost();
        return "http".equalsIgnoreCase(uri.getScheme())
                && ("localhost".equalsIgnoreCase(host) || "127.0.0.1".equals(host) || "::1".equals(host));
    }
}
