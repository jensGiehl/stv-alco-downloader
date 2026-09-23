package de.agiehl;

import java.net.URI;
import java.time.Clock;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
final class PageParser {

    private static final DateTimeFormatter GERMAN_DATE = DateTimeFormatter.ofPattern("dd.MM.uuuu");
    private static final Pattern DOCUMENT_ID = Pattern.compile("(?:^|[?&])ID=(\\d+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern CONTRACT_ID = Pattern.compile("(?:^|[?&])id=([^&]+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern ACCOUNT_RANGE = Pattern.compile(
            "(?i)Zeitraum:\\s*(?:chevron_left\\s*)?(\\d{2}\\.\\d{2}\\.\\d{4})\\s*bis:\\s*(\\d{2}\\.\\d{2}\\.\\d{4})");
    private static final Pattern BALANCE_RANGE = Pattern.compile(
            "(?i)Angaben für den Zeitraum:\\s*(?:chevron_left\\s*)?(\\d{2}\\.\\d{2}\\.\\d{4})\\s*-\\s*(\\d{2}\\.\\d{2}\\.\\d{4})");

    private final Clock clock;

    @Autowired
    PageParser() {
        this(Clock.systemUTC());
    }

    PageParser(Clock clock) {
        this.clock = clock;
    }

    SectionData parse(String section, String contractId, HttpResult result, Map<String, String> context) {
        Document document = Jsoup.parse(result.bodyAsString(), result.uri().toString());
        boolean supplierDetail = "obj-lieferanten-detail".equals(section);
        Document structuredDocument = supplierDetail ? supplierAddressDocument(document) : document;
        Map<String, String> fields = parseFields(structuredDocument, section);
        List<TableData> tables = supplierDetail ? List.of() : parseTables(document);
        List<ItemData> items = supplierDetail ? List.of() : parseItems(document, section);
        List<LinkData> links = supplierDetail ? List.of() : parseLinks(document, result.uri());
        List<DiscoveredDocument> documents = supplierDetail
                ? List.of()
                : parseDocuments(document, result.uri(), section, contractId);
        String pageText = supplierDetail ? supplierPageText(structuredDocument, fields) : clean(document.body().text());
        return new SectionData(section, contractId, result.uri().toString(), clock.instant(), clean(document.title()),
                pageText, Map.copyOf(context), fields, tables, items, links, documents);
    }

    List<ContractReference> parseContracts(HttpResult result) {
        Document document = Jsoup.parse(result.bodyAsString(), result.uri().toString());
        Map<String, ContractReference> contracts = new LinkedHashMap<>();
        for (Element link : document.select("a[href*='aktion=anzeigen'][href*='id=']")) {
            String href = link.attr("href");
            Matcher matcher = CONTRACT_ID.matcher(href);
            if (!matcher.find()) {
                continue;
            }
            String id = matcher.group(1);
            Element row = link.closest("tr");
            String description = clean(row == null ? link.text() : row.text());
            String contractNumber = "";
            if (row != null) {
                for (Element cell : row.select("td")) {
                    String text = clean(cell.text());
                    if (text.matches("\\d+-\\d+-\\d+")) {
                        contractNumber = text;
                        break;
                    }
                }
            }
            URI selectionUri = UriTools.resolve(result.uri(), href);
            contracts.putIfAbsent(id, new ContractReference(id, contractNumber, description, selectionUri));
        }
        if (contracts.isEmpty()) {
            throw new CrawlerException("No contracts were found after login");
        }
        return List.copyOf(contracts.values());
    }

    List<SelectOption> parseSettlementPeriods(HttpResult result) {
        Document document = Jsoup.parse(result.bodyAsString(), result.uri().toString());
        List<SelectOption> options = new ArrayList<>();
        for (Element option : document.select("select[name=abrechnungszeit] option")) {
            options.add(new SelectOption(option.val(), clean(option.text()), option.hasAttr("selected")));
        }
        return List.copyOf(options);
    }

    List<URI> parseSettlementDetailLinks(HttpResult result) {
        Document document = Jsoup.parse(result.bodyAsString(), result.uri().toString());
        Set<URI> links = new LinkedHashSet<>();
        for (Element link : document.select("a[href*='kontoauszug.php'][href*='KTO=']")) {
            URI uri = UriTools.resolve(result.uri(), link.attr("href"));
            if (uri != null) {
                links.add(uri);
            }
        }
        return List.copyOf(links);
    }

    List<URI> parseBalanceDetailLinks(HttpResult result) {
        Document document = Jsoup.parse(result.bodyAsString(), result.uri().toString());
        Set<URI> links = new LinkedHashSet<>();
        for (Element link : document.select("a[href*='kontoauszug.php'][href*='ID='][href*='NAME='][href*='KTNTYP=']")) {
            URI uri = UriTools.resolve(result.uri(), link.attr("href"));
            if (uri != null) {
                links.add(uri);
            }
        }
        return List.copyOf(links);
    }

    List<URI> parseTableLinks(HttpResult result, String endpoint) {
        Document document = Jsoup.parse(result.bodyAsString(), result.uri().toString());
        Set<URI> links = new LinkedHashSet<>();
        for (Element link : document.select("table a[href]")) {
            URI uri = UriTools.resolve(result.uri(), link.attr("href"));
            if (uri != null && uri.getPath().toLowerCase().endsWith(endpoint.toLowerCase())) {
                links.add(uri);
            }
        }
        return List.copyOf(links);
    }

    Optional<String> parsePeriodRange(HttpResult result) {
        return parsePeriodDates(result)
                .map(period -> GERMAN_DATE.format(period.start()) + " - " + GERMAN_DATE.format(period.end()));
    }

    Optional<LocalDate> parsePeriodEnd(HttpResult result) {
        return parsePeriodDates(result).map(PeriodDates::end);
    }

    private Optional<PeriodDates> parsePeriodDates(HttpResult result) {
        Document document = Jsoup.parse(result.bodyAsString(), result.uri().toString());
        String text = clean(document.body().text());
        for (Pattern pattern : List.of(ACCOUNT_RANGE, BALANCE_RANGE)) {
            Matcher matcher = pattern.matcher(text);
            if (matcher.find()) {
                try {
                    return Optional.of(new PeriodDates(
                            LocalDate.parse(matcher.group(1), GERMAN_DATE),
                            LocalDate.parse(matcher.group(2), GERMAN_DATE)));
                } catch (DateTimeParseException ignored) {
                    return Optional.empty();
                }
            }
        }
        return Optional.empty();
    }

    Optional<URI> findPeriodNavigation(HttpResult result, String direction) {
        Document document = Jsoup.parse(result.bodyAsString(), result.uri().toString());
        for (Element link : document.select("a[href]")) {
            String href = link.attr("href");
            if (href.matches("(?i).*([?&])id=" + Pattern.quote(direction) + "(?:&.*)?$")) {
                return Optional.ofNullable(UriTools.resolve(result.uri(), href));
            }
        }
        return Optional.empty();
    }

    Optional<Boolean> isBookingOverviewEmpty(HttpResult result) {
        Document document = Jsoup.parse(result.bodyAsString(), result.uri().toString());
        Optional<Element> bookingTable = document.select("table").stream()
                .filter(table -> table.select("th").stream()
                        .map(Element::text)
                        .map(PageParser::clean)
                        .anyMatch(header -> header.equalsIgnoreCase("Buchungstext")))
                .findFirst();
        if (bookingTable.isEmpty() && !clean(document.body().text()).toLowerCase()
                .contains("ihre buchungsübersicht")) {
            return Optional.empty();
        }
        return bookingTable.map(table -> !hasDataRows(table)).or(() -> Optional.of(true));
    }

    boolean hasBalanceData(HttpResult result) {
        Document document = Jsoup.parse(result.bodyAsString(), result.uri().toString());
        return document.select("table").stream()
                .filter(table -> table.select("th").stream()
                        .map(Element::text)
                        .map(PageParser::clean)
                        .anyMatch(header -> header.equalsIgnoreCase("Bezeichnung")))
                .anyMatch(this::hasDataRows);
    }

    boolean hasFeature(HttpResult result, String pathFragment) {
        Document document = Jsoup.parse(result.bodyAsString(), result.uri().toString());
        return document.select("a[href*='" + pathFragment + "']").stream().findAny().isPresent();
    }

    SectionData filterToCurrentMonth(SectionData sectionData, LocalDate currentDate) {
        List<TableData> filteredTables = new ArrayList<>();
        boolean foundDatedTable = false;
        Set<String> retainedDocumentHrefs = new LinkedHashSet<>();
        for (TableData table : sectionData.tables()) {
            boolean dated = !table.headers().isEmpty() && clean(table.headers().getFirst()).equalsIgnoreCase("Datum");
            if (!dated) {
                filteredTables.add(table);
                continue;
            }
            foundDatedTable = true;
            List<List<CellData>> rows = table.rows().stream()
                    .filter(row -> isInMonth(row, currentDate))
                    .toList();
            rows.stream().flatMap(List::stream).flatMap(cell -> cell.links().stream())
                    .map(LinkData::href).filter(href -> href.contains("showpdf.php"))
                    .forEach(retainedDocumentHrefs::add);
            filteredTables.add(new TableData(table.headers(), rows));
        }
        if (!foundDatedTable) {
            return sectionData;
        }
        List<DiscoveredDocument> documents = sectionData.documents().stream()
                .filter(document -> retainedDocumentHrefs.contains(document.href()))
                .toList();
        List<LinkData> links = sectionData.links().stream()
                .filter(link -> !link.href().contains("showpdf.php") || retainedDocumentHrefs.contains(link.href()))
                .toList();
        String filteredText = filteredTables.stream()
                .flatMap(table -> table.rows().stream())
                .flatMap(List::stream)
                .map(CellData::text)
                .filter(text -> !text.isBlank())
                .reduce((left, right) -> left + " | " + right)
                .orElse("");
        Map<String, String> context = new LinkedHashMap<>(sectionData.context());
        context.put("filteredMonth", currentDate.getYear() + "-" + "%02d".formatted(currentDate.getMonthValue()));
        return new SectionData(sectionData.section(), sectionData.contractId(), sectionData.sourceUrl(),
                sectionData.capturedAt(), sectionData.title(), filteredText, Map.copyOf(context),
                sectionData.fields(), List.copyOf(filteredTables), sectionData.items(), links, documents);
    }

    private boolean isInMonth(List<CellData> row, LocalDate currentDate) {
        if (row.isEmpty()) {
            return false;
        }
        try {
            LocalDate date = LocalDate.parse(clean(row.getFirst().text()), GERMAN_DATE);
            return date.getYear() == currentDate.getYear() && date.getMonth() == currentDate.getMonth();
        } catch (DateTimeParseException ignored) {
            return false;
        }
    }

    private List<TableData> parseTables(Document document) {
        List<TableData> tables = new ArrayList<>();
        for (Element table : document.select("table")) {
            List<String> headers = table.select("th").stream().map(Element::text).map(PageParser::clean).toList();
            List<List<CellData>> rows = new ArrayList<>();
            for (Element row : table.select("tr")) {
                List<Element> cells = row.select("td");
                if (cells.isEmpty()) {
                    continue;
                }
                rows.add(cells.stream().map(cell -> new CellData(clean(cell.text()), parseLinks(cell, document.location())))
                        .toList());
            }
            tables.add(new TableData(headers, List.copyOf(rows)));
        }
        return List.copyOf(tables);
    }

    private List<ItemData> parseItems(Document document, String section) {
        List<ItemData> items = new ArrayList<>();
        for (Element button : document.select("button[data-target],button[aria-controls]")) {
            String targetId = button.hasAttr("data-target") ? button.attr("data-target") : button.attr("aria-controls");
            targetId = targetId.replaceFirst("^#", "");
            Element target = document.getElementById(targetId);
            if (target != null) {
                items.add(new ItemData(clean(button.text()), clean(target.text()), parseLinks(target, document.location())));
            }
        }
        if ("beschluss".equals(section)) {
            for (Element heading : document.select("p")) {
                Element content = heading.nextElementSibling();
                if (content != null && "div".equals(content.normalName())
                        && !clean(heading.text()).isBlank() && !clean(content.text()).isBlank()) {
                    ItemData item = new ItemData(clean(heading.text()), clean(content.text()),
                            parseLinks(content, document.location()));
                    if (!items.contains(item)) {
                        items.add(item);
                    }
                }
            }
        }
        return List.copyOf(items);
    }

    private Map<String, String> parseFields(Document document, String section) {
        Map<String, String> fields = new LinkedHashMap<>();
        for (Element label : document.select("label")) {
            Element control = label.attr("for").isBlank() ? null : document.getElementById(label.attr("for"));
            if (control != null) {
                String value = control.hasAttr("value") ? control.val() : control.text();
                if (!value.isBlank() && !"password".equalsIgnoreCase(control.attr("type"))) {
                    fields.putIfAbsent(clean(label.text()), clean(value));
                }
            }
        }
        for (Element label : document.select("b,strong")) {
            String key = clean(label.text());
            if (!key.endsWith(":")) {
                continue;
            }
            String value = followingText(label);
            if (!value.isBlank()) {
                fields.putIfAbsent(key.substring(0, key.length() - 1), value);
            }
        }
        for (Element term : document.select("dt")) {
            Element value = term.nextElementSibling();
            if (value != null && "dd".equals(value.normalName()) && !clean(value.text()).isBlank()) {
                fields.putIfAbsent(withoutTrailingColon(clean(term.text())), clean(value.text()));
            }
        }
        if (section.startsWith("obj-lieferanten")) {
            for (Element address : document.select("address,[class*=adress],[id*=adress],[class*=anschrift],[id*=anschrift]")) {
                if (!clean(address.text()).isBlank()) {
                    fields.putIfAbsent("Adresse", clean(address.text()));
                }
            }
            for (Element row : document.select("tr")) {
                List<Element> cells = row.select("td");
                if (row.select("a[href]").isEmpty() && cells.size() == 2
                        && !clean(cells.getFirst().text()).isBlank()
                        && !clean(cells.getLast().text()).isBlank()) {
                    fields.putIfAbsent(withoutTrailingColon(clean(cells.getFirst().text())),
                            clean(cells.getLast().text()));
                }
            }
            for (Element row : document.select(".row")) {
                List<Element> columns = row.children();
                if (row.select("a[href]").isEmpty() && columns.size() == 2) {
                    String key = clean(columns.getFirst().text());
                    String value = clean(columns.getLast().text());
                    if (!key.isBlank() && key.length() <= 80 && !value.isBlank() && !key.equals(value)) {
                        fields.putIfAbsent(withoutTrailingColon(key), value);
                    }
                }
            }
        }
        return Map.copyOf(fields);
    }

    private Document supplierAddressDocument(Document document) {
        Document copy = document.clone();
        copy.select("table").stream()
                .filter(table -> !table.select("a[href*='obj-lieferanten.php']").isEmpty())
                .forEach(Element::remove);
        copy.select("nav,footer,script,style").remove();
        return copy;
    }

    private String supplierPageText(Document document, Map<String, String> fields) {
        if (!fields.isEmpty()) {
            return fields.entrySet().stream()
                    .map(entry -> entry.getKey() + ": " + entry.getValue())
                    .reduce((left, right) -> left + " | " + right)
                    .orElse("");
        }
        return clean(document.body().text());
    }

    private boolean hasDataRows(Element table) {
        return table.select("tr").stream().anyMatch(row -> {
            List<Element> cells = row.select("td");
            if (cells.isEmpty()) {
                return false;
            }
            if (cells.size() == 1 && (cells.getFirst().hasAttr("colspan")
                    || clean(cells.getFirst().text()).matches("(?i).*(keine|nicht vorhanden|leer).*"))) {
                return false;
            }
            return cells.stream().map(Element::text).map(PageParser::clean).anyMatch(text -> !text.isBlank());
        });
    }

    private String withoutTrailingColon(String value) {
        return value.endsWith(":") ? value.substring(0, value.length() - 1).strip() : value;
    }

    private String followingText(Element element) {
        Node sibling = element.nextSibling();
        while (sibling != null) {
            if (sibling instanceof TextNode textNode && !clean(textNode.text()).isBlank()) {
                return clean(textNode.text());
            }
            if (sibling instanceof Element siblingElement && !clean(siblingElement.text()).isBlank()) {
                return clean(siblingElement.text());
            }
            sibling = sibling.nextSibling();
        }
        return "";
    }

    private List<LinkData> parseLinks(Element root, String baseUri) {
        return parseLinks(root, URI.create(baseUri));
    }

    private List<LinkData> parseLinks(Element root, URI baseUri) {
        List<LinkData> links = new ArrayList<>();
        for (Element link : root.select("a[href]")) {
            URI resolved = UriTools.resolve(baseUri, link.attr("href"));
            if (resolved != null) {
                links.add(new LinkData(clean(link.text()), resolved.toString()));
            }
        }
        return List.copyOf(links);
    }

    private List<DiscoveredDocument> parseDocuments(Document document, URI baseUri, String section,
                                                      String contractId) {
        List<DiscoveredDocument> documents = new ArrayList<>();
        for (Element link : document.select("a[href*='showpdf.php']")) {
            URI uri = UriTools.resolve(baseUri, link.attr("href"));
            if (uri == null) {
                continue;
            }
            Matcher matcher = DOCUMENT_ID.matcher(uri.getRawQuery() == null ? "" : uri.getRawQuery());
            String id = matcher.find() ? matcher.group(1) : uri.toString();
            documents.add(new DiscoveredDocument(id, clean(link.text()), uri.toString(), section,
                    baseUri.toString(), contractId));
        }
        return List.copyOf(documents);
    }

    private static String clean(String value) {
        return value == null ? "" : value.replaceAll("\\s+", " ").trim();
    }

    private record PeriodDates(LocalDate start, LocalDate end) {
    }
}
