package de.agiehl;

import java.net.URI;

final class UriTools {

    private UriTools() {
    }

    static URI resolve(URI base, String href) {
        if (href == null || href.isBlank() || href.startsWith("#") || href.startsWith("mailto:")
                || href.startsWith("tel:")) {
            return null;
        }
        try {
            if (href.startsWith("?")) {
                String baseWithoutQuery = base.toString().split("[?#]", 2)[0];
                return URI.create(baseWithoutQuery + href.replace(" ", "%20"));
            }
            return base.resolve(URI.create(href.replace(" ", "%20")));
        } catch (IllegalArgumentException failure) {
            throw new CrawlerException("Invalid internal link on " + base.getPath(), failure);
        }
    }
}
