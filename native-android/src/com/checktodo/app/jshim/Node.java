package com.checktodo.app.jshim;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Minimal Jsoup-compatible node used by the hand-written KSoup/Jsoup shim. */
public class Node {
    final String nodeName;
    final Map<String, String> attributes = new LinkedHashMap<>();
    final List<Node> childNodes = new ArrayList<>();
    Node parent;
    String text = "";

    Node(String nodeName) {
        this.nodeName = nodeName == null ? "" : nodeName.toLowerCase();
    }

    public String nodeName() {
        return nodeName;
    }

    public Node parent() {
        return parent;
    }

    public List<Node> childNodes() {
        return Collections.unmodifiableList(childNodes);
    }

    public String attr(String key) {
        if (key == null) return "";
        if (key.equals("abs:href")) return attr("href");
        return attributes.getOrDefault(key.toLowerCase(), "");
    }

    public boolean hasAttr(String key) {
        return key != null && attributes.containsKey(key.toLowerCase());
    }

    void appendText(String value) {
        if (nodeName.equals("script") || nodeName.equals("style")) {
            text += value;
            return;
        }
        Node textNode = new Node("#text");
        textNode.text = Entities.decode(value);
        appendChild(textNode);
    }

    void appendChild(Node child) {
        child.parent = this;
        childNodes.add(child);
    }
}
