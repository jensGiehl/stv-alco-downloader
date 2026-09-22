package de.agiehl;

import java.nio.file.Path;

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
        try {
            properties.validate();
            runWithSingleSessionRestart();
        } catch (ConfigurationException | AuthenticationException exception) {
            exitCode = 2;
            LOGGER.error("Crawler configuration or authentication failed: {}", exception.getMessage());
        } catch (StorageException exception) {
            exitCode = 4;
            LOGGER.error("Writing the backup failed: {}", exception.getMessage());
        } catch (CrawlerException exception) {
            exitCode = 3;
            LOGGER.error("The crawl did not complete: {}", exception.getMessage());
        } catch (RuntimeException exception) {
            exitCode = 3;
            LOGGER.error("The crawl failed unexpectedly: {}", exception.getClass().getSimpleName());
            LOGGER.debug("Unexpected crawler failure", exception);
        }
    }

    @Override
    public int getExitCode() {
        return exitCode;
    }

    private void runWithSingleSessionRestart() {
        for (int attempt = 1; attempt <= 2; attempt++) {
            try {
                Path snapshot = crawler.crawl();
                LOGGER.info("Backup completed successfully in {}", snapshot);
                exitCode = 0;
                return;
            } catch (SessionExpiredException exception) {
                if (attempt == 2) {
                    throw exception;
                }
                LOGGER.warn("The ALCO session expired; restarting the crawl once");
            }
        }
    }
}
