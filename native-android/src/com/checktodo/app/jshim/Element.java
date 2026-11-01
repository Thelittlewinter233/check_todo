package com.checktodo.app.jshim;

import java.util.ArrayList;
import java.util.List;

/** Minimal Jsoup-compatible element. */
public class Element extends Node {
    public Element(String tag) {
        super(tag);
    }

    public String tag() {
        return nodeName;
    }

    public String id() {
        return attr("id");
    }

    public String className() {
        return attr("class");
    }

    public boolean hasClass(String className) {
        String value = attr("class");
        for (String item : value.split("\\s+")) {
            if (item.equals(className)) return true;
        }
        return false;
    }

    public String text() {
        return Selector.visibleText(this);
    }

    public String ownText() {
        StringBuilder out = new StringBuilder();
        for (Node child : childNodes) {
            if (child.nodeName.equals("#text")) out.append(child.text).append(' ');
        }
        return Selector.normalizeWhitespace(out.toString());
    }

    public String val() {
        return attr("value");
    }

    public String data() {
        StringBuilder out = new StringBuilder();
        collectData(this, out);
        return out.toString();
    }

    public String html() {
        StringBuilder out = new StringBuilder();
        collectRawHtml(this, out);
        return out.toString();
    }

    public Element parent() {
        return (Element) parent;
    }

    public Elements parents() {
        Elements out = new Elements();
        Node current = parent;
        while (current instanceof Element) {
            out.add((Element) current);
            current = current.parent;
        }
        return out;
    }

    public Elements children() {
        Elements out = new Elements();
        for (Node child : childNodes) {
            if (child instanceof Element) out.add((Element) child);
        }
        return out;
    }

    public Elements siblings() {
        Elements out = new Elements();
        if (parent == null) return out;
        for (Node child : parent.childNodes) {
            if (child != this && child instanceof Element) out.add((Element) child);
        }
        return out;
    }

    public Element firstChild() {
        for (Node child : childNodes) {
            if (child instanceof Element) return (Element) child;
        }
        return null;
    }

    public Element child(int index) {
        return children().get(index);
    }

    public int elementSiblingIndex() {
        int index = 0;
        if (parent != null) {
            for (Node child : parent.childNodes) {
                if (child == this) return index;
                if (child instanceof Element) index++;
            }
        }
        return index;
    }

    public Elements getElementsByTag(String tag) {
        Elements out = new Elements();
        String wanted = tag == null ? "" : tag.toLowerCase();
        collectByTag(this, wanted, out);
        return out;
    }

    public Elements getElementsByClass(String className) {
        return select("." + className);
    }

    public Element getElementById(String id) {
        Elements found = select("#" + id);
        return found.isEmpty() ? null : found.first();
    }

    public Elements select(String query) {
        return Selector.select(query, this);
    }

    public Element selectFirst(String query) {
        Elements found = select(query);
        return found.isEmpty() ? null : found.first();
    }

    private static void collectByTag(Element root, String tag, Elements out) {
        for (Node child : root.childNodes) {
            if (child instanceof Element) {
                Element element = (Element) child;
                if (element.nodeName.equals(tag)) out.add(element);
                collectByTag(element, tag, out);
            }
        }
    }

    private static void collectData(Node node, StringBuilder out) {
        if (node.nodeName.equals("script") || node.nodeName.equals("style")) out.append(node.text);
        for (Node child : node.childNodes) collectData(child, out);
    }

    private static void collectRawHtml(Node node, StringBuilder out) {
        if (node.text != null && !node.text.isEmpty()) out.append(node.text);
        for (Node child : node.childNodes) collectRawHtml(child, out);
    }
}
