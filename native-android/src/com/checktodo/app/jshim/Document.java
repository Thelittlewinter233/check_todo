package com.checktodo.app.jshim;

/** Minimal Jsoup-compatible document. */
public final class Document extends Element {
    public Document() {
        super("#document");
    }

    public Element body() {
        return selectFirst("body");
    }

    public Element head() {
        return selectFirst("head");
    }

    public String title() {
        Element element = selectFirst("title");
        return element == null ? "" : element.text();
    }
}
