package de.agiehl;

import java.net.URI;
import java.util.regex.Pattern;

final class UriTools {

    private static final Pattern UNESCAPED_PERCENT = Pattern.compile("%(?![0-9a-fA-F]{2})");

    private UriTools() {
    }

    static URI resolve(URI base, String href) {
        if (href == null || href.isBlank() || href.startsWith("#") || href.startsWith("mailto:")
                || href.startsWith("tel:")) {
            return null;
        }
        try {
            String encodedHref = encodeInvalidCharacters(href);
            if (href.startsWith("?")) {
                String baseWithoutQuery = base.toString().split("[?#]", 2)[0];
                return URI.create(baseWithoutQuery + encodedHref);
            }
            return base.resolve(URI.create(encodedHref));
        } catch (IllegalArgumentException failure) {
            throw new CrawlerException("Invalid internal link on " + base.getPath(), failure);
        }
    }

    private static String encodeInvalidCharacters(String href) {
        String encodedSpaces = href.replace(" ", "%20");
        return UNESCAPED_PERCENT.matcher(encodedSpaces).replaceAll("%25");
    }
}
