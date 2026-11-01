package com.checktodo.app.jshim;

/** Jsoup-compatible parser entry point (hand-written, zero third-party). */
public final class Jsoup {
    private Jsoup() {
    }

    public static Document parse(String html) {
        Document document = new Document();
        HtmlTreeBuilder.appendHtml(document, html == null ? "" : html);
        return document;
    }

    public static Document parse(String html, String baseUri) {
        return parse(html);
    }
}
