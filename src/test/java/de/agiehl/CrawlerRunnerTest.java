package de.agiehl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.DefaultApplicationArguments;

class CrawlerRunnerTest {

    @TempDir
    Path temporaryDirectory;

    private AlcoProperties properties;
    private AlcoCrawler crawler;
    private TelegramNotifier notifier;
    private CrawlerRunner runner;

    @BeforeEach
    void setUp() {
        properties = new AlcoProperties();
        properties.setUsername("test-user");
        properties.setPassword("test-password");
        properties.setOutputDir(temporaryDirectory);
        crawler = mock(AlcoCrawler.class);
        notifier = mock(TelegramNotifier.class);
        runner = new CrawlerRunner(properties, crawler, notifier);
    }

    @Test
    void notifiesOnceWithSuccessfulCrawlCounts() {
        doAnswer(invocation -> {
            CrawlProgress progress = invocation.getArgument(0);
            progress.startAttempt();
            progress.pages(3);
            progress.records(7L);
            progress.documentsDownloaded(2, 100);
            return temporaryDirectory;
        }).when(crawler).crawl(any(CrawlProgress.class));

        run();

        CrawlProgress progress = notifiedProgress(0);
        assertThat(runner.getExitCode()).isZero();
        assertThat(progress.attempts()).isEqualTo(1);
        assertThat(progress.pages()).isEqualTo(3);
        assertThat(progress.records()).isEqualTo(7);
        assertThat(progress.documentsDownloaded()).isEqualTo(2);
        verify(crawler).crawl(progress);
        verifyNoMoreInteractions(crawler);
    }

    @Test
    void notifiesConfigurationFailureWithoutStartingTheCrawl() {
        properties.setUsername(" ");

        run();

        CrawlProgress progress = notifiedProgress(2);
        assertThat(runner.getExitCode()).isEqualTo(2);
        assertThat(progress.attempts()).isZero();
        assertThat(progress.records()).isZero();
        assertThat(progress.documentsDownloaded()).isZero();
        verifyNoInteractions(crawler);
    }

    @ParameterizedTest
    @MethodSource("crawlFailures")
    void notifiesMappedCrawlFailureWithPartialCounts(RuntimeException failure, int expectedExitCode) {
        doAnswer(invocation -> {
            CrawlProgress progress = invocation.getArgument(0);
            progress.startAttempt();
            progress.pages(2);
            progress.records(5L);
            progress.documentsDownloaded(1, 50);
            throw failure;
        }).when(crawler).crawl(any(CrawlProgress.class));

        run();

        CrawlProgress progress = notifiedProgress(expectedExitCode);
        assertThat(runner.getExitCode()).isEqualTo(expectedExitCode);
        assertThat(progress.attempts()).isEqualTo(1);
        assertThat(progress.pages()).isEqualTo(2);
        assertThat(progress.records()).isEqualTo(5);
        assertThat(progress.documentsDownloaded()).isEqualTo(1);
        verify(crawler).crawl(progress);
        verifyNoMoreInteractions(crawler);
    }

    @Test
    void notifiesOnlyFinalSuccessAfterRestartWithCountsFromTheLastAttempt() {
        doAnswer(invocation -> {
            CrawlProgress progress = invocation.getArgument(0);
            progress.startAttempt();
            if (progress.attempts() == 1) {
                progress.records(90L);
                progress.documentsDownloaded(30, 3000);
                throw new SessionExpiredException("Session expired");
            }
            assertThat(progress.records()).isZero();
            assertThat(progress.documentsDownloaded()).isZero();
            progress.records(7L);
            progress.documentsDownloaded(2, 100);
            return temporaryDirectory;
        }).when(crawler).crawl(any(CrawlProgress.class));

        run();

        CrawlProgress progress = notifiedProgress(0);
        assertThat(runner.getExitCode()).isZero();
        assertThat(progress.attempts()).isEqualTo(2);
        assertThat(progress.sessionRestarts()).isEqualTo(1);
        assertThat(progress.records()).isEqualTo(7);
        assertThat(progress.documentsDownloaded()).isEqualTo(2);
        verify(crawler, times(2)).crawl(progress);
        verifyNoMoreInteractions(crawler);
    }

    @Test
    void notifiesOnlyFinalFailureWhenTheRestartAlsoLosesItsSession() {
        doAnswer(invocation -> {
            CrawlProgress progress = invocation.getArgument(0);
            progress.startAttempt();
            if (progress.attempts() == 1) {
                progress.records(90L);
                progress.documentsDownloaded(30, 3000);
            } else {
                progress.records(1L);
                progress.documentsDownloaded(1, 50);
            }
            throw new SessionExpiredException("Session expired");
        }).when(crawler).crawl(any(CrawlProgress.class));

        run();

        CrawlProgress progress = notifiedProgress(3);
        assertThat(runner.getExitCode()).isEqualTo(3);
        assertThat(progress.attempts()).isEqualTo(2);
        assertThat(progress.sessionRestarts()).isEqualTo(1);
        assertThat(progress.records()).isEqualTo(1);
        assertThat(progress.documentsDownloaded()).isEqualTo(1);
        verify(crawler, times(2)).crawl(progress);
        verifyNoMoreInteractions(crawler);
    }

    @Test
    void generatesReportWithoutCrawlingOrNotifying() throws Exception {
        Path snapshot = Files.createDirectory(temporaryDirectory.resolve("snapshot"));
        Files.writeString(snapshot.resolve("manifest.json"), """
                {"status":"COMPLETE","startedAt":"2026-10-13T08:00:00Z","period":"ALL",
                 "contracts":0,"pages":0,"documents":0}
                """, StandardCharsets.UTF_8);
        properties.setReportSource(snapshot);

        run();

        assertThat(runner.getExitCode()).isZero();
        assertThat(snapshot.resolve("index.html")).isRegularFile();
        verifyNoInteractions(crawler, notifier);
    }

    @Test
    void doesNotNotifyWhenReportSourceValidationFails() {
        properties.setReportSource(temporaryDirectory.resolve("missing-snapshot"));

        run();

        assertThat(runner.getExitCode()).isEqualTo(2);
        verifyNoInteractions(crawler, notifier);
    }

    @Test
    void doesNotNotifyWhenReportSnapshotIsMalformed() throws Exception {
        Path snapshot = Files.createDirectory(temporaryDirectory.resolve("snapshot"));
        Files.writeString(snapshot.resolve("manifest.json"), "invalid JSON", StandardCharsets.UTF_8);
        properties.setReportSource(snapshot);

        run();

        assertThat(runner.getExitCode()).isEqualTo(4);
        verifyNoInteractions(crawler, notifier);
    }

    private static Stream<Arguments> crawlFailures() {
        return Stream.of(
                Arguments.of(new ConfigurationException("Invalid configuration"), 2),
                Arguments.of(new AuthenticationException("Authentication failed"), 2),
                Arguments.of(new StorageException("Storage failed", new IllegalStateException()), 4),
                Arguments.of(new CrawlerException("Crawl failed"), 3),
                Arguments.of(new IllegalStateException("Unexpected failure"), 3));
    }

    private void run() {
        runner.run(new DefaultApplicationArguments());
    }

    private CrawlProgress notifiedProgress(int expectedExitCode) {
        ArgumentCaptor<CrawlProgress> progress = ArgumentCaptor.forClass(CrawlProgress.class);
        verify(notifier).notifyResult(eq(expectedExitCode), progress.capture());
        verifyNoMoreInteractions(notifier);
        return progress.getValue();
    }
}
