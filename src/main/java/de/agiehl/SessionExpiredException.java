package de.agiehl;

public final class SessionExpiredException extends CrawlerException {

    public SessionExpiredException(String message) {
        super(message);
    }
}
