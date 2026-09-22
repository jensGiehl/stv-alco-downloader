package de.agiehl;

import java.net.URI;
import java.nio.file.Path;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

import org.springframework.beans.factory.annotation.Autowired;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
final class AlcoCrawler {

    private static final int MAX_PERIOD_STEPS = 100;
    private static final Logger LOGGER = LoggerFactory.getLogger(AlcoCrawler.class);

    private final AlcoProperties properties;
    private final AlcoHttpClient client;
    private final PageParser parser;
    private final Clock clock;

    @Autowired
    AlcoCrawler(AlcoProperties properties, AlcoHttpClient client, PageParser parser) {
        this(properties, client, parser, Clock.systemUTC());
    }

    AlcoCrawler(AlcoProperties properties, AlcoHttpClient client, PageParser parser, Clock clock) {
        this.properties = properties;
        this.client = client;
        this.parser = parser;
        this.clock = clock;
    }

    Path crawl() {
        return crawl(new CrawlProgress());
    }

    Path crawl(CrawlProgress progress) {
        progress.startAttempt();
        properties.validate();
        LOGGER.info("Authenticating with ALCO");
        client.authenticate();
        LOGGER.info("Authentication successful");
        SnapshotStore store = new SnapshotStore(properties, clock);
        progress.snapshotCreated(store.root());
        LOGGER.info("Created snapshot directory {}", store.root());
        AttachmentCollector attachments = new AttachmentCollector();
        try {
            LOGGER.info("Loading available contracts");
            HttpResult initialHome = client.get(client.resolve("/homeV.php"));
            List<ContractReference> contracts = parser.parseContracts(initialHome);
            progress.contractsDiscovered(contracts.size());
            store.writeContracts(contracts);
            LOGGER.info("Found {} contract(s)", contracts.size());
            for (int index = 0; index < contracts.size(); index++) {
                ContractReference contract = contracts.get(index);
                LOGGER.info("Processing contract {}/{}: number='{}', description='{}'", index + 1,
                        contracts.size(), contract.contractNumber(), contract.description());
                crawlContract(contract, store, attachments);
                progress.contractCompleted();
                LOGGER.info("Completed contract {}/{}: number='{}'", index + 1, contracts.size(),
                        contract.contractNumber());
            }
            progress.documentsDiscovered(attachments.size());
            LOGGER.info("Downloading {} unique document(s)", attachments.size());
            List<DocumentData> documents = attachments.downloadAll(client, store);
            progress.documentsDownloaded(documents.size(), documents.stream().mapToLong(DocumentData::size).sum());
            store.writeDocuments(documents);
            progress.pages(store.pageCount());
            progress.warnings(store.warningCount());
            store.complete(clock.instant());
            return store.root();
        } catch (RuntimeException exception) {
            progress.pages(store.pageCount());
            progress.documentsDiscovered(attachments.size());
            progress.warnings(store.warningCount());
            store.fail(clock.instant(), safeFailure(exception));
            throw exception;
        }
    }

    private void crawlContract(ContractReference contract, SnapshotStore store, AttachmentCollector attachments) {
        HttpResult home = client.get(contract.selectionUri());
        processPage("home", contract.id(), home, Map.of(), store, attachments, false);

        crawlStaticPage("payment", "/vertragszahlung.php", contract, store, attachments);
        crawlStaticPage("unit", "/einheit.php", contract, store, attachments);
        HttpResult messages = crawlStaticPage("messages", "/infosend.php", contract, store, attachments);
        List<URI> messageDetails = parser.parseIndexedDetailLinks(messages, "infosend.php",
                Pattern.compile("(?:^|&)ID=\\d+(?:&|$)", Pattern.CASE_INSENSITIVE));
        LOGGER.info("Found {} message detail page(s) for contract '{}'", messageDetails.size(),
                contract.contractNumber());
        for (URI detail : messageDetails) {
            HttpResult message = client.get(detail);
            processPage("message-detail", contract.id(), message, Map.of(), store, attachments, false);
        }

        Map<String, String> optionalFeatures = new LinkedHashMap<>();
        optionalFeatures.put("mda_objekte.php", "object-information");
        optionalFeatures.put("showinfo.php", "repairs");
        optionalFeatures.put("beschluss.php", "resolutions");
        optionalFeatures.put("doc-beirat.php", "advisory-documents");
        for (Map.Entry<String, String> feature : optionalFeatures.entrySet()) {
            if (parser.hasFeature(home, feature.getKey())) {
                LOGGER.info("Optional section '{}' is available for contract '{}'", feature.getValue(),
                        contract.contractNumber());
                crawlStaticPage(feature.getValue(), "/" + feature.getKey(), contract, store, attachments);
            }
        }
        if (parser.hasFeature(home, "obj-lieferanten.php")) {
            LOGGER.info("Optional section 'suppliers' is available for contract '{}'", contract.contractNumber());
            HttpResult suppliers = crawlStaticPage("suppliers", "/obj-lieferanten.php", contract, store,
                    attachments);
            List<URI> supplierDetails = parser.parseIndexedDetailLinks(suppliers, "obj-lieferanten.php",
                    Pattern.compile("(?:^|&)aktion=anzeigen(?:&.*)?&id=\\d+(?:&|$)",
                            Pattern.CASE_INSENSITIVE));
            LOGGER.info("Found {} supplier detail page(s) for contract '{}'", supplierDetails.size(),
                    contract.contractNumber());
            for (URI detail : supplierDetails) {
                HttpResult supplier = client.get(detail);
                processPage("supplier-detail", contract.id(), supplier, Map.of(), store, attachments, false);
            }
        }

        crawlPeriodPage("account", contract.id(), client.resolve("/kontoauszug.php?AUFRUFTYP=V"), store,
                attachments, true);
        if (parser.hasFeature(home, "mda-salden.php")) {
            crawlPeriodPage("balances", contract.id(), client.resolve("/mda-salden.php"), store, attachments,
                    false);
        }
        crawlSettlements(contract.id(), store, attachments);
    }

    private HttpResult crawlStaticPage(String section, String path, ContractReference contract, SnapshotStore store,
                                       AttachmentCollector attachments) {
        HttpResult result = client.get(client.resolve(path));
        processPage(section, contract.id(), result, Map.of(), store, attachments, false);
        return result;
    }

    private void crawlPeriodPage(String section, String contractId, URI initialUri, SnapshotStore store,
                                 AttachmentCollector attachments, boolean filterMonth) {
        LOGGER.info("Determining latest period for section '{}' and contract '{}'", section, contractId);
        HttpResult current = moveToLatestPeriod(initialUri);
        if (properties.getPeriod() != CrawlPeriod.ALL) {
            Map<String, String> context = periodContext(current, "YEAR");
            LOGGER.info("Processing section '{}' for selected period '{}'", section,
                    context.getOrDefault("period", "unknown"));
            processPage(section, contractId, current, context, store, attachments,
                    filterMonth && properties.getPeriod() == CrawlPeriod.CURRENT_MONTH);
            return;
        }

        Set<String> visitedRanges = new LinkedHashSet<>();
        for (int step = 0; step < MAX_PERIOD_STEPS; step++) {
            String range = parser.parsePeriodRange(current).orElse("unknown:" + current.uri());
            if (!visitedRanges.add(range)) {
                return;
            }
            LOGGER.info("Processing section '{}' for period '{}'", section, range);
            processPage(section, contractId, current, periodContext(current, "YEAR"), store, attachments, false);
            Optional<URI> previous = parser.findPeriodNavigation(current, "zurueck");
            if (previous.isEmpty()) {
                return;
            }
            HttpResult candidate = client.get(previous.get());
            String candidateRange = parser.parsePeriodRange(candidate).orElse("unknown:" + candidate.uri());
            if (visitedRanges.contains(candidateRange)) {
                return;
            }
            current = candidate;
        }
        throw new CrawlerException("Period navigation exceeded the safety limit on " + initialUri.getPath());
    }

    private HttpResult moveToLatestPeriod(URI initialUri) {
        HttpResult current = client.get(initialUri);
        if (reachesCurrentPeriod(current)) {
            return current;
        }
        Set<String> visitedRanges = new LinkedHashSet<>();
        for (int step = 0; step < MAX_PERIOD_STEPS; step++) {
            String currentRange = parser.parsePeriodRange(current).orElse("unknown:" + current.uri());
            if (!visitedRanges.add(currentRange)) {
                return current;
            }
            Optional<URI> forward = parser.findPeriodNavigation(current, "vor");
            if (forward.isEmpty()) {
                return current;
            }
            HttpResult candidate = client.get(forward.get());
            String candidateRange = parser.parsePeriodRange(candidate).orElse("unknown:" + candidate.uri());
            if (candidateRange.equals(currentRange) || visitedRanges.contains(candidateRange)) {
                return current;
            }
            if (reachesCurrentPeriod(candidate)) {
                return candidate;
            }
            current = candidate;
        }
        throw new CrawlerException("Forward period navigation exceeded the safety limit on " + initialUri.getPath());
    }

    private boolean reachesCurrentPeriod(HttpResult result) {
        LocalDate today = LocalDate.now(clock.withZone(ZoneOffset.UTC));
        Optional<LocalDate> end = parser.parsePeriodEnd(result);
        boolean reached = end.filter(date -> !date.isBefore(today)).isPresent();
        if (reached) {
            LOGGER.info("Reached the current period ending on {}; skipping navigation into future periods",
                    end.orElseThrow());
        }
        return reached;
    }

    private void crawlSettlements(String contractId, SnapshotStore store, AttachmentCollector attachments) {
        URI uri = client.resolve("/obj-abrechnung.php");
        HttpResult initial = client.get(uri);
        List<SelectOption> available = parser.parseSettlementPeriods(initial);
        LOGGER.info("Found {} settlement period(s) for contract '{}'", available.size(), contractId);
        if (available.isEmpty()) {
            processPage("settlement-overview", contractId, initial, Map.of("granularity", "YEAR"), store,
                    attachments, false);
            return;
        }
        List<SelectOption> selected = properties.getPeriod() == CrawlPeriod.ALL
                ? available
                : List.of(selectCurrentOrLatest(available));
        for (SelectOption option : selected) {
            LOGGER.info("Processing settlement period '{}' for contract '{}'", option.label(), contractId);
            HttpResult overview = client.postForm(uri, Map.of("abrechnungszeit", option.value()));
            Map<String, String> context = Map.of(
                    "period", option.label(),
                    "periodValue", option.value(),
                    "granularity", "YEAR");
            processPage("settlement-overview", contractId, overview, context, store, attachments, false);
            List<URI> detailUris = parser.parseSettlementDetailLinks(overview);
            LOGGER.info("Found {} settlement detail page(s) for period '{}'", detailUris.size(), option.label());
            for (URI detailUri : detailUris) {
                HttpResult detail = client.get(detailUri);
                processPage("settlement-detail", contractId, detail, context, store, attachments,
                        properties.getPeriod() == CrawlPeriod.CURRENT_MONTH);
            }
        }
    }

    private SelectOption selectCurrentOrLatest(List<SelectOption> options) {
        String currentYear = Integer.toString(LocalDate.now(clock).getYear());
        return options.stream().filter(option -> option.label().contains(currentYear)).findFirst()
                .orElse(options.getFirst());
    }

    private void processPage(String section, String contractId, HttpResult result, Map<String, String> context,
                             SnapshotStore store, AttachmentCollector attachments, boolean filterCurrentMonth) {
        SectionData parsed = parser.parse(section, contractId, result, context);
        SectionData exported = filterCurrentMonth
                ? parser.filterToCurrentMonth(parsed, LocalDate.now(clock.withZone(ZoneOffset.UTC)))
                : parsed;
        store.writeSection(exported, result);
        attachments.register(exported);
        LOGGER.info("Saved section '{}' for contract '{}': tables={}, items={}, links={}, documents={}", section,
                contractId, exported.tables().size(), exported.items().size(), exported.links().size(),
                exported.documents().size());
    }

    private Map<String, String> periodContext(HttpResult result, String granularity) {
        Map<String, String> context = new LinkedHashMap<>();
        parser.parsePeriodRange(result).ifPresent(range -> context.put("period", range));
        context.put("granularity", granularity);
        context.put("requestedPeriod", properties.getPeriod().name());
        return Map.copyOf(context);
    }

    private String safeFailure(RuntimeException exception) {
        String message = exception.getMessage();
        if (message == null || message.isBlank()) {
            return exception.getClass().getSimpleName();
        }
        return message.replace(properties.getUsername(), "<redacted>")
                .replace(properties.getPassword(), "<redacted>");
    }
}
