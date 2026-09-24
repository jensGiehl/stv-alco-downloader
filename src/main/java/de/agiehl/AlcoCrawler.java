package de.agiehl;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

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
                HttpResult selectedHome = client.get(contract.selectionUri());
                if (index == 0) {
                    crawlPrimaryContract(contract, selectedHome, store, attachments);
                }
                crawlAccount(contract.id(), store, attachments);
                progress.contractCompleted();
                LOGGER.info("Completed contract {}/{}: number='{}'", index + 1, contracts.size(),
                        contract.contractNumber());
            }
            progress.documentsDiscovered(attachments.size());
            List<DocumentData> documents = attachments.documents();
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

    private void crawlPrimaryContract(ContractReference contract, HttpResult home, SnapshotStore store,
                                      AttachmentCollector attachments) {
        processPage("home", contract.id(), home, Map.of(), store, attachments, false);

        crawlStaticPage("vertragszahlung", "/vertragszahlung.php", contract, store, attachments);
        crawlStaticPage("einheit", "/einheit.php", contract, store, attachments);

        Map<String, String> optionalFeatures = new LinkedHashMap<>();
        optionalFeatures.put("showinfo.php", "showinfo");
        optionalFeatures.put("beschluss.php", "beschluss");
        optionalFeatures.put("doc-beirat.php", "doc-beirat");
        for (Map.Entry<String, String> feature : optionalFeatures.entrySet()) {
            if (parser.hasFeature(home, feature.getKey())) {
                LOGGER.info("Optional section '{}' is available for contract '{}'", feature.getValue(),
                        contract.contractNumber());
                crawlStaticPage(feature.getValue(), "/" + feature.getKey(), contract, store, attachments);
            }
        }
        if (parser.hasFeature(home, "mda_objekte.php")) {
            crawlStaticPage("mda-objekte", "/mda_objekte.php", contract, store, attachments);
        } else if (parser.hasFeature(home, "mda-objekte.php")) {
            crawlStaticPage("mda-objekte", "/mda-objekte.php", contract, store, attachments);
        }
        if (parser.hasFeature(home, "obj-lieferanten.php")) {
            LOGGER.info("Optional section 'suppliers' is available for contract '{}'", contract.contractNumber());
            HttpResult suppliers = crawlStaticPage("obj-lieferanten", "/obj-lieferanten.php", contract, store,
                    attachments);
            List<SupplierReference> supplierDetails = parser.parseSupplierReferences(suppliers);
            LOGGER.info("Found {} supplier detail page(s) for contract '{}'", supplierDetails.size(),
                    contract.contractNumber());
            for (SupplierReference detail : supplierDetails) {
                HttpResult supplier = client.get(detail.uri());
                processPage("obj-lieferanten-detail", contract.id(), supplier, supplierContext(detail), store,
                        attachments, false);
            }
        }

        if (parser.hasFeature(home, "mda-salden.php")) {
            crawlBalances(contract.id(), store, attachments);
        }
        crawlSettlements(contract.id(), store, attachments);
    }

    private HttpResult crawlStaticPage(String section, String path, ContractReference contract, SnapshotStore store,
                                       AttachmentCollector attachments) {
        HttpResult result = client.get(client.resolve(path));
        processPage(section, contract.id(), result, Map.of(), store, attachments, false);
        return result;
    }

    private void crawlAccount(String contractId, SnapshotStore store, AttachmentCollector attachments) {
        URI initialUri = client.resolve("/kontoauszug.php?AUFRUFTYP=V");
        HttpResult current = client.get(initialUri);
        if (properties.getPeriod() != CrawlPeriod.ALL) {
            Map<String, String> context = periodContext(current, "YEAR");
            LOGGER.info("Processing account statement for contract '{}' and selected period '{}'", contractId,
                    context.getOrDefault("period", "unknown"));
            if (!parser.isBookingOverviewEmpty(current).orElse(false)) {
                processPage("kontoauszug", contractId, current, context, store, attachments,
                        properties.getPeriod() == CrawlPeriod.CURRENT_MONTH);
            }
            return;
        }

        Set<String> visitedRanges = new LinkedHashSet<>();
        for (int step = 0; step < MAX_PERIOD_STEPS; step++) {
            if (parser.isBookingOverviewEmpty(current).orElse(false)) {
                LOGGER.info("Reached the first empty account statement for contract '{}'", contractId);
                return;
            }
            String range = periodKey(current);
            if (!visitedRanges.add(range)) {
                return;
            }
            LOGGER.info("Processing account statement for contract '{}' and period '{}'", contractId, range);
            processPage("kontoauszug", contractId, current, periodContext(current, "YEAR"), store, attachments,
                    false);
            Optional<URI> previous = parser.findPeriodNavigation(current, "zurueck");
            if (previous.isEmpty()) {
                return;
            }
            HttpResult candidate = client.get(previous.get());
            if (visitedRanges.contains(periodKey(candidate))) {
                return;
            }
            current = candidate;
        }
        throw new CrawlerException("Account statement navigation exceeded the safety limit for contract "
                + contractId);
    }

    private void crawlBalances(String contractId, SnapshotStore store, AttachmentCollector attachments) {
        URI initialUri = client.resolve("/mda-salden.php");
        HttpResult current = client.get(initialUri);
        if (properties.getPeriod() != CrawlPeriod.ALL) {
            if (parser.hasBalanceData(current)) {
                crawlBalancePeriod(contractId, current, store, attachments);
            }
            return;
        }
        Set<String> visitedRanges = new LinkedHashSet<>();
        for (int step = 0; step < MAX_PERIOD_STEPS; step++) {
            if (!parser.hasBalanceData(current)) {
                LOGGER.info("Reached the first empty balance period for contract '{}'", contractId);
                return;
            }
            String range = periodKey(current);
            if (!visitedRanges.add(range)) {
                return;
            }
            crawlBalancePeriod(contractId, current, store, attachments);
            Optional<URI> previous = parser.findPeriodNavigation(current, "zurueck");
            if (previous.isEmpty()) {
                return;
            }
            HttpResult candidate = client.get(previous.get());
            if (visitedRanges.contains(periodKey(candidate))) {
                return;
            }
            current = candidate;
        }
        throw new CrawlerException("Balance navigation exceeded the safety limit for contract " + contractId);
    }

    private void crawlBalancePeriod(String contractId, HttpResult overview, SnapshotStore store,
                                    AttachmentCollector attachments) {
        Map<String, String> context = periodContext(overview, "YEAR");
        String period = context.getOrDefault("period", "unknown");
        LOGGER.info("Processing balances for contract '{}' and period '{}'", contractId, period);
        processPage("mda-salden", contractId, overview, context, store, attachments, false);
        List<URI> details = parser.parseBalanceDetailLinks(overview);
        LOGGER.info("Found {} balance account(s) for period '{}'", details.size(), period);
        for (URI detailUri : details) {
            Map<String, String> detailContext = new LinkedHashMap<>(context);
            detailContext.putAll(balanceAccountContext(detailUri));
            HttpResult detail = client.get(detailUri);
            processPage("mda-salden-kontoauszug", contractId, detail, Map.copyOf(detailContext), store,
                    attachments, false);
        }
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
        SectionData withAttachments = attachments.capture(exported, client, store);
        store.writeSection(withAttachments, result);
        LOGGER.info("Saved section '{}' for contract '{}': tables={}, items={}, links={}, documents={}", section,
                contractId, withAttachments.tables().size(), withAttachments.items().size(),
                withAttachments.links().size(), withAttachments.documents().size());
    }

    private Map<String, String> periodContext(HttpResult result, String granularity) {
        Map<String, String> context = new LinkedHashMap<>();
        parser.parsePeriodRange(result).ifPresent(range -> context.put("period", range));
        context.put("granularity", granularity);
        context.put("requestedPeriod", properties.getPeriod().name());
        return Map.copyOf(context);
    }

    private String periodKey(HttpResult result) {
        return parser.parsePeriodRange(result)
                .orElseGet(() -> "content:" + Integer.toUnsignedString(result.bodyAsString().hashCode()));
    }

    private Map<String, String> supplierContext(SupplierReference supplier) {
        Map<String, String> parameters = queryParameters(supplier.uri());
        Map<String, String> context = new LinkedHashMap<>();
        Optional.ofNullable(parameters.get("id")).ifPresent(value -> context.put("supplierId", value));
        if (!supplier.name().isBlank()) {
            context.put("supplierName", supplier.name());
        }
        return Map.copyOf(context);
    }

    private Map<String, String> balanceAccountContext(URI uri) {
        Map<String, String> parameters = queryParameters(uri);
        Map<String, String> context = new LinkedHashMap<>();
        Optional.ofNullable(parameters.get("ID")).ifPresent(value -> context.put("accountId", value));
        Optional.ofNullable(parameters.get("NAME")).ifPresent(value -> context.put("accountName", value));
        Optional.ofNullable(parameters.get("KTNTYP")).ifPresent(value -> context.put("accountType", value));
        return Map.copyOf(context);
    }

    private Map<String, String> queryParameters(URI uri) {
        Map<String, String> parameters = new LinkedHashMap<>();
        if (uri.getRawQuery() == null) {
            return parameters;
        }
        for (String pair : uri.getRawQuery().split("&")) {
            String[] parts = pair.split("=", 2);
            String key = URLDecoder.decode(parts[0], StandardCharsets.UTF_8);
            String value = parts.length == 2 ? URLDecoder.decode(parts[1], StandardCharsets.UTF_8) : "";
            parameters.putIfAbsent(key, value);
        }
        return parameters;
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
