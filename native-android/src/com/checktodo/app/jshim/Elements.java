package com.checktodo.app.jshim;

import java.util.AbstractList;
import java.util.ArrayList;

/** Minimal Jsoup-compatible Elements list. */
public final class Elements extends AbstractList<Element> {
    private final ArrayList<Element> delegate = new ArrayList<>();

    public Elements() {
    }

    public Elements(Iterable<Element> elements) {
        if (elements != null) {
            for (Element element : elements) add(element);
        }
    }

    @Override
    public Element get(int index) {
        return delegate.get(index);
    }

    @Override
    public int size() {
        return delegate.size();
    }

    @Override
    public void add(int index, Element element) {
        delegate.add(index, element);
    }

    @Override
    public Element set(int index, Element element) {
        return delegate.set(index, element);
    }

    @Override
    public Element remove(int index) {
        return delegate.remove(index);
    }

    public Element first() {
        return isEmpty() ? null : get(0);
    }

    public Element last() {
        return isEmpty() ? null : get(size() - 1);
    }

    public Elements select(String query) {
        Elements out = new Elements();
        for (Element element : this) out.addAll(Selector.select(query, element));
        return out;
    }

    public String text() {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < size(); i++) {
            if (i > 0) out.append(' ');
            out.append(get(i).text());
        }
        return Selector.normalizeWhitespace(out.toString());
    }

    public String attr(String key) {
        for (Element element : this) {
            if (element.hasAttr(key)) return element.attr(key);
        }
        return "";
    }

    public String outerHtml() {
        return toString();
    }
}
