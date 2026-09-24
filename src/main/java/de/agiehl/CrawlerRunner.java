package de.agiehl;

import java.net.URI;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.ExitCodeGenerator;
import org.springframework.stereotype.Component;

@Component
final class CrawlerRunner implements ApplicationRunner, ExitCodeGenerator {

    private static final Logger LOGGER = LoggerFactory.getLogger(CrawlerRunner.class);

    private final AlcoProperties properties;
    private final AlcoCrawler crawler;
    private int exitCode;

    CrawlerRunner(AlcoProperties properties, AlcoCrawler crawler) {
        this.properties = properties;
        this.crawler = crawler;
    }

    @Override
    public void run(ApplicationArguments args) {
        long startedNanos = System.nanoTime();
        CrawlProgress progress = new CrawlProgress();
        boolean reportOnly = properties.isReportOnly();
        try {
            if (reportOnly) {
                generateReport();
                exitCode = 0;
                return;
            }
            properties.validate();
            LOGGER.info("Starting ALCO backup: baseUrl={}, period={}, outputDirectory={}", safeBaseUrl(),
                    properties.getPeriod(), properties.getOutputDir().toAbsolutePath().normalize());
            runWithSingleSessionRestart(progress);
        } catch (ConfigurationException | AuthenticationException exception) {
            exitCode = 2;
            String operation = reportOnly ? "Report configuration" : "Crawler configuration or authentication";
            LOGGER.error("{} failed: {}", operation, exception.getMessage());
        } catch (StorageException exception) {
            exitCode = 4;
            LOGGER.error("Writing the backup failed: {}", exception.getMessage());
        } catch (CrawlerException exception) {
            exitCode = 3;
            LOGGER.error("The crawl did not complete: {}", exception.getMessage());
        } catch (RuntimeException exception) {
            exitCode = 3;
            String operation = reportOnly ? "report generation" : "crawl";
            LOGGER.error("The {} failed unexpectedly: {}", operation, exception.getClass().getSimpleName());
            LOGGER.debug("Unexpected {} failure", operation, exception);
        } finally {
            if (reportOnly) {
                logReportSummary(elapsedMillis(startedNanos));
            } else {
                logSummary(progress, elapsedMillis(startedNanos));
            }
        }
    }

    @Override
    public int getExitCode() {
        return exitCode;
    }

    private void runWithSingleSessionRestart(CrawlProgress progress) {
        for (int attempt = 1; attempt <= 2; attempt++) {
            try {
                crawler.crawl(progress);
                exitCode = 0;
                return;
            } catch (SessionExpiredException exception) {
                if (attempt == 2) {
                    throw exception;
                }
                progress.sessionRestarted();
                LOGGER.warn("The ALCO session expired; restarting the crawl once");
            }
        }
    }

    private void generateReport() {
        properties.validateReportSource();
        var source = properties.getReportSource().toAbsolutePath().normalize();
        LOGGER.info("Generating ALCO report from snapshot JSON: snapshot={}", source);
        new HtmlReportWriter(source).write();
    }

    private void logReportSummary(long durationMillis) {
        String status = exitCode == 0 ? "SUCCESS" : "FAILED";
        var source = properties.getReportSource();
        String snapshot = source == null ? "-" : source.toAbsolutePath().normalize().toString();
        if (exitCode == 0) {
            LOGGER.info("ALCO report generation finished: status={}, durationMs={}, snapshot={}", status,
                    durationMillis, snapshot);
        } else {
            LOGGER.error("ALCO report generation finished: status={}, durationMs={}, snapshot={}", status,
                    durationMillis, snapshot);
        }
    }

    private void logSummary(CrawlProgress progress, long durationMillis) {
        String status = exitCode == 0 ? "SUCCESS" : "FAILED";
        String summary = "ALCO backup finished: status={}, durationMs={}, attempts={}, sessionRestarts={}, "
                + "contractsCompleted={}, contractsDiscovered={}, pages={}, documentsDownloaded={}, "
                + "documentsDiscovered={}, attachmentBytes={}, warnings={}, snapshot={}";
        Object[] values = {
                status,
                durationMillis,
                progress.attempts(),
                progress.sessionRestarts(),
                progress.contractsCompleted(),
                progress.contractsDiscovered(),
                progress.pages(),
                progress.documentsDownloaded(),
                progress.documentsDiscovered(),
                progress.attachmentBytes(),
                progress.warnings(),
                progress.snapshot()
        };
        if (exitCode == 0) {
            LOGGER.info(summary, values);
        } else {
            LOGGER.error(summary, values);
        }
    }

    private long elapsedMillis(long startedNanos) {
        return (System.nanoTime() - startedNanos) / 1_000_000;
    }

    private String safeBaseUrl() {
        URI baseUrl = properties.getBaseUrl();
        String host = baseUrl.getHost().contains(":") ? "[" + baseUrl.getHost() + "]" : baseUrl.getHost();
        String port = baseUrl.getPort() < 0 ? "" : ":" + baseUrl.getPort();
        String path = baseUrl.getPath() == null ? "" : baseUrl.getPath();
        return baseUrl.getScheme() + "://" + host + port + path;
    }
}
