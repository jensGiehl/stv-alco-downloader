package de.agiehl;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Map;

import org.junit.jupiter.api.Test;

class PageParserTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-22T10:15:30Z"), ZoneOffset.UTC);
    private final PageParser parser = new PageParser(CLOCK);

    @Test
    void parsesContractsTablesCollapsedItemsAndDocuments() {
        String html = """
                <html><head><title>ALCO-web</title></head><body>
                <table><tr><th>Link</th><th>Vertrag</th><th>Objekt</th></tr>
                <tr><td><a href="?aktion=anzeigen&id=0">check_box</a></td><td>71-10-1</td><td>Objekt A</td></tr>
                <tr><td><a href="?aktion=anzeigen&id=1">check_box_outline_blank</a></td><td>71-20-1</td><td>Objekt A</td></tr>
                </table>
                <button data-target="#detail">Beschluss Nr. 1</button>
                <div id="detail">Beschlusstext <a href="showpdf.php?ID=123">Anlage</a></div>
                </body></html>
                """;
        HttpResult result = html("https://stv.alco-web.de/homeV.php", html);

        var contracts = parser.parseContracts(result);
        var section = parser.parse("home", "0", result, Map.of());

        assertThat(contracts).extracting(ContractReference::id).containsExactly("0", "1");
        assertThat(contracts).extracting(ContractReference::contractNumber).containsExactly("71-10-1", "71-20-1");
        assertThat(section.tables()).hasSize(1);
        assertThat(section.items()).singleElement().satisfies(item -> {
            assertThat(item.heading()).contains("Beschluss Nr. 1");
            assertThat(item.content()).contains("Beschlusstext");
        });
        assertThat(section.documents()).singleElement().satisfies(document -> {
            assertThat(document.id()).isEqualTo("123");
            assertThat(document.href()).isEqualTo("https://stv.alco-web.de/showpdf.php?ID=123");
        });
    }

    @Test
    void filtersDatedRowsAndDocumentsToCurrentMonth() {
        String html = """
                <html><body><div>Zeitraum: 01.01.2026 bis: 31.12.2026</div>
                <table><tr><th>Datum</th><th>Buchungstext</th></tr>
                <tr><td>03.09.2026</td><td><a href="showpdf.php?ID=1">September</a></td></tr>
                <tr><td>04.08.2026</td><td><a href="showpdf.php?ID=2">August</a></td></tr>
                </table></body></html>
                """;
        HttpResult result = html("https://stv.alco-web.de/kontoauszug.php", html);
        SectionData parsed = parser.parse("account", "0", result, Map.of());

        SectionData filtered = parser.filterToCurrentMonth(parsed, LocalDate.of(2026, 9, 22));

        assertThat(parser.parsePeriodEnd(result)).contains(LocalDate.of(2026, 12, 31));
        assertThat(filtered.tables().getFirst().rows()).hasSize(1);
        assertThat(filtered.documents()).extracting(DiscoveredDocument::id).containsExactly("1");
        assertThat(filtered.context()).containsEntry("filteredMonth", "2026-09");
        assertThat(filtered.pageText()).contains("September").doesNotContain("August");
    }

    @Test
    void preservesQueryLinksWhenResolvingRelativeUris() {
        URI resolved = UriTools.resolve(URI.create("https://stv.alco-web.de/homeV.php"),
                "?aktion=anzeigen&id=2");

        assertThat(resolved).hasToString("https://stv.alco-web.de/homeV.php?aktion=anzeigen&id=2");
    }

    @Test
    void parsesResolutionParagraphsAndFollowingDivisions() {
        HttpResult result = html("https://stv.alco-web.de/beschluss.php", """
                <html><body>
                <p>Beschluss zur Fassade</p>
                <div>Die Sanierung wurde beschlossen. <a href="showpdf.php?ID=77">Protokoll</a></div>
                </body></html>
                """);

        SectionData section = parser.parse("beschluss", "0", result, Map.of());

        assertThat(section.items()).singleElement().satisfies(item -> {
            assertThat(item.heading()).isEqualTo("Beschluss zur Fassade");
            assertThat(item.content()).isEqualTo("Die Sanierung wurde beschlossen. Protokoll");
            assertThat(item.links()).singleElement().extracting(LinkData::href)
                    .isEqualTo("https://stv.alco-web.de/showpdf.php?ID=77");
        });
    }

    @Test
    void detectsEmptyAccountOverviewAndBalanceData() {
        HttpResult emptyAccount = html("https://stv.alco-web.de/kontoauszug.php", """
                <html><body><h2>Ihre Buchungsübersicht</h2>
                <table><tr><th>Datum</th><th>Buchungstext</th></tr></table></body></html>
                """);
        HttpResult balances = html("https://stv.alco-web.de/mda-salden.php", """
                <html><body><table><tr><th>Bezeichnung</th><th>Saldo</th></tr>
                <tr><td><a href="kontoauszug.php?ID=0&amp;NAME=Vorschuss&amp;KTNTYP=GE">Vorschuss</a></td>
                <td>10,00 EUR</td></tr></table></body></html>
                """);

        assertThat(parser.isBookingOverviewEmpty(emptyAccount)).contains(true);
        assertThat(parser.hasBalanceData(balances)).isTrue();
        assertThat(parser.parseBalanceDetailLinks(balances)).singleElement().satisfies(uri ->
                assertThat(uri.toString()).contains("ID=0", "NAME=Vorschuss", "KTNTYP=GE"));
    }

    @Test
    void parsesSupplierMasterDataFromDefinitionListsAndTwoColumnTables() {
        HttpResult result = html("https://stv.alco-web.de/obj-lieferanten.php?aktion=anzeigen&id=9", """
                <html><body><dl><dt>Firma:</dt><dd>Beispiel GmbH</dd></dl>
                <table><tr><td>Telefon:</td><td>01234 56789</td></tr></table></body></html>
                """);

        SectionData section = parser.parse("obj-lieferanten-detail", "0", result, Map.of());

        assertThat(section.fields()).containsEntry("Firma", "Beispiel GmbH")
                .containsEntry("Telefon", "01234 56789");
    }

    @Test
    void encodesUnescapedPercentSignsWithoutChangingValidPercentEncoding() {
        URI resolved = UriTools.resolve(URI.create("https://stv.alco-web.de/mda-salden.php"),
                "kontoauszug.php?ID=11&NAME=Wartung BHKW 70%&KTNTYP=GV");
        URI alreadyEncoded = UriTools.resolve(URI.create("https://stv.alco-web.de/mda-salden.php"),
                "kontoauszug.php?ID=1&NAME=Erhaltungsr%C3%BCcklage&KTNTYP=GE");

        assertThat(resolved).hasToString(
                "https://stv.alco-web.de/kontoauszug.php?ID=11&NAME=Wartung%20BHKW%2070%25&KTNTYP=GV");
        assertThat(alreadyEncoded).hasToString(
                "https://stv.alco-web.de/kontoauszug.php?ID=1&NAME=Erhaltungsr%C3%BCcklage&KTNTYP=GE");
    }

    private HttpResult html(String uri, String body) {
        return new HttpResult(URI.create(uri), 200, "text/html; charset=UTF-8",
                body.getBytes(StandardCharsets.UTF_8));
    }
}
