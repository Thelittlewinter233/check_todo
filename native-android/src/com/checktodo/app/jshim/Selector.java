package com.checktodo.app.jshim;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Selector engine for the syntax actually used by WakeUp parsers. */
public final class Selector {
    private Selector() {
    }

    public static Elements select(String query, Element root) {
        Elements out = new Elements();
        if (root == null || query == null) return out;
        for (String part : splitTopLevel(query, ',')) {
            String trimmed = part.trim();
            if (trimmed.isEmpty()) continue;
            List<CompoundSelector> chain = parseChain(trimmed);
            collect(root, chain, out);
        }
        return ordered(out);
    }

    static String visibleText(Node node) {
        StringBuilder out = new StringBuilder();
        collectVisibleText(node, out);
        return normalizeWhitespace(out.toString());
    }

    static String normalizeWhitespace(String value) {
        if (value == null || value.isEmpty()) return "";
        return value.replace('\u00a0', ' ').replace('\u3000', ' ').trim().replaceAll("\\s+", " ");
    }

    private static void collectVisibleText(Node node, StringBuilder out) {
        if (node.nodeName.equals("script") || node.nodeName.equals("style")) return;
        out.append(Entities.decode(node.text)).append(' ');
        for (Node child : node.childNodes) collectVisibleText(child, out);
    }

    private static void collect(Element root, List<CompoundSelector> chain, Elements out) {
        for (Element candidate : descendants(root)) {
            if (matchesChain(candidate, chain)) out.add(candidate);
        }
    }

    private static Elements descendants(Element root) {
        Elements out = new Elements();
        collectDescendants(root, out);
        return out;
    }

    private static void collectDescendants(Element element, Elements out) {
        for (Node child : element.childNodes) {
            if (child instanceof Element) {
                Element childElement = (Element) child;
                out.add(childElement);
                collectDescendants(childElement, out);
            }
        }
    }

    private static boolean matchesChain(Element candidate, List<CompoundSelector> chain) {
        return matchesCompound(candidate, chain.get(chain.size() - 1))
                && matchesAncestry(candidate, chain, chain.size() - 2);
    }

    private static boolean matchesAncestry(Element candidate, List<CompoundSelector> chain, int index) {
        if (index < 0) return true;
        CompoundSelector selector = chain.get(index);
        if (selector.child) {
            Element parent = candidate.parent();
            return parent != null && matchesCompound(parent, selector)
                    && matchesAncestry(parent, chain, index - 1);
        }
        Element current = candidate.parent();
        while (current != null) {
            if (matchesCompound(current, selector) && matchesAncestry(current, chain, index - 1)) {
                return true;
            }
            current = current.parent();
        }
        return false;
    }

    private static boolean matchesCompound(Element element, CompoundSelector selector) {
        if (!selector.tag.isEmpty() && !selector.tag.equals("*")
                && !selector.tag.equals(element.nodeName)) return false;
        if (!selector.id.isEmpty() && !selector.id.equals(element.id())) return false;
        for (String className : selector.classes) {
            if (!element.hasClass(className)) return false;
        }
        for (AttributeSelector attribute : selector.attributes) {
            if (!matchesAttribute(element, attribute)) return false;
        }
        for (PseudoSelector pseudo : selector.pseudos) {
            if (!matchesPseudo(element, pseudo)) return false;
        }
        return true;
    }

    private static boolean matchesAttribute(Element element, AttributeSelector selector) {
        boolean present = element.hasAttr(selector.name);
        String value = element.attr(selector.name);
        if (selector.operator.isEmpty()) return present;
        if (!present) return false;
        switch (selector.operator) {
            case "=":
                return value.equals(selector.value);
            case "^=":
                return !selector.value.isEmpty() && value.startsWith(selector.value);
            case "$=":
                return !selector.value.isEmpty() && value.endsWith(selector.value);
            case "*=":
                return !selector.value.isEmpty() && value.contains(selector.value);
            case "~=":
                for (String item : value.split("\\s+")) {
                    if (item.equals(selector.value)) return true;
                }
                return false;
            default:
                return false;
        }
    }

    private static boolean matchesPseudo(Element element, PseudoSelector pseudo) {
        if (pseudo.name.equals("contains")) {
            return element.text().contains(pseudo.argument);
        }
        if (pseudo.name.equals("containsown")) {
            return element.ownText().contains(pseudo.argument);
        }
        if (pseudo.name.equals("not")) {
            return !matchesCompound(element, parseCompound(pseudo.argument));
        }
        if (pseudo.name.equals("gt")) {
            int index;
            try {
                index = Integer.parseInt(pseudo.argument.trim());
            } catch (NumberFormatException error) {
                return false;
            }
            return siblingIndex(element) > index;
        }
        if (pseudo.name.equals("lt")) {
            int index;
            try {
                index = Integer.parseInt(pseudo.argument.trim());
            } catch (NumberFormatException error) {
                return false;
            }
            return siblingIndex(element) < index;
        }
        if (pseudo.name.equals("eq")) {
            int index;
            try {
                index = Integer.parseInt(pseudo.argument.trim());
            } catch (NumberFormatException error) {
                return false;
            }
            return siblingIndex(element) == index;
        }
        if (pseudo.name.equals("first-child")) return siblingIndex(element) == 0;
        if (pseudo.name.equals("last-child")) return element.parent() == null
                || siblingIndex(element) == element.parent().children().size() - 1;
        if (pseudo.name.startsWith("nth-child")) {
            int index = parseNth(pseudo.argument);
            return index >= 0 && siblingIndex(element) + 1 == index;
        }
        return false;
    }

    private static int siblingIndex(Element element) {
        return element.elementSiblingIndex();
    }

    private static int parseNth(String argument) {
        if (argument == null) return -1;
        String value = argument.replace(" ", "");
        if (value.equals("odd")) return 1;
        if (value.equals("even")) return 2;
        int plus = value.indexOf('+');
        int index = plus > 0 ? value.indexOf('+', plus + 1) : -1;
        if (index > 0) value = value.substring(index + 1);
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException error) {
            return -1;
        }
    }

    private static List<CompoundSelector> parseChain(String query) {
        List<CompoundSelector> chain = new ArrayList<>();
        int index = 0;
        while (index < query.length()) {
            while (index < query.length() && Character.isWhitespace(query.charAt(index))) index++;
            int start = index;
            while (index < query.length()) {
                char ch = query.charAt(index);
                if (ch == '>' || ch == '+' || ch == '~') break;
                if (Character.isWhitespace(query.charAt(index))) {
                    int next = index;
                    while (next < query.length() && Character.isWhitespace(query.charAt(next))) next++;
                    if (next >= query.length() || query.charAt(next) == '>'
                            || query.charAt(next) == '+' || query.charAt(next) == '~') {
                        index = next;
                        continue;
                    }
                    break;
                }
                index = skipSelectorAtom(query, index);
            }
            if (index > start) {
                chain.add(parseCompound(query.substring(start, index)));
            }
            if (index < query.length()) {
                char combinator = query.charAt(index++);
                if (!chain.isEmpty()) chain.get(chain.size() - 1).child = combinator == '>';
            }
        }
        if (chain.isEmpty()) throw new IllegalArgumentException("Empty selector: " + query);
        return chain;
    }

    private static int skipSelectorAtom(String query, int index) {
        char ch = query.charAt(index);
        if (ch == '[') return findClosing(query, index, '[', ']') + 1;
        if (ch == '(') return findClosing(query, index, '(', ')') + 1;
        if (ch == ':' || ch == '.') return index + 1;
        return index + 1;
    }

    private static int findClosing(String query, int start, char open, char close) {
        int depth = 0;
        for (int i = start; i < query.length(); i++) {
            char ch = query.charAt(i);
            if (ch == open) depth++;
            else if (ch == close && --depth == 0) return i;
        }
        return query.length() - 1;
    }

    private static CompoundSelector parseCompound(String source) {
        CompoundSelector selector = new CompoundSelector();
        int index = 0;
        while (index < source.length()) {
            char ch = source.charAt(index);
            if (ch == '*') {
                selector.tag = "*";
                index++;
            } else if (Character.isLetterOrDigit(ch)) {
                int start = index;
                while (index < source.length() && Character.isLetterOrDigit(source.charAt(index))) index++;
                if (selector.tag.isEmpty()) selector.tag = source.substring(start, index).toLowerCase();
            } else if (ch == '#') {
                int start = ++index;
                while (index < source.length() && isIdentifier(source.charAt(index))) index++;
                selector.id = source.substring(start, index);
            } else if (ch == '.') {
                int start = ++index;
                while (index < source.length() && isIdentifier(source.charAt(index))) index++;
                selector.classes.add(source.substring(start, index));
            } else if (ch == '[') {
                int end = findClosing(source, index, '[', ']');
                selector.attributes.add(parseAttribute(source.substring(index + 1, end)));
                index = end + 1;
            } else if (ch == ':') {
                index++;
                int start = index;
                while (index < source.length() && Character.isLetterOrDigit(source.charAt(index))) index++;
                String name = source.substring(start, index).toLowerCase();
                String argument = "";
                if (index < source.length() && source.charAt(index) == '(') {
                    int end = findClosing(source, index, '(', ')');
                    argument = source.substring(index + 1, end);
                    index = end + 1;
                }
                selector.pseudos.add(new PseudoSelector(name, argument));
            } else {
                index++;
            }
        }
        return selector;
    }

    private static AttributeSelector parseAttribute(String source) {
        AttributeSelector selector = new AttributeSelector();
        int index = skipWhitespace(source, 0);
        int start = index;
        while (index < source.length() && isIdentifier(source.charAt(index))) index++;
        selector.name = source.substring(start, index).toLowerCase();
        index = skipWhitespace(source, index);
        if (index >= source.length() || source.charAt(index) == ']') return selector;
        int operatorStart = index;
        while (index < source.length() && "=^$*~".indexOf(source.charAt(index)) >= 0) index++;
        selector.operator = source.substring(operatorStart, index);
        index = skipWhitespace(source, index);
        if (index < source.length() && (source.charAt(index) == '"' || source.charAt(index) == '\'')) {
            char quote = source.charAt(index++);
            start = index;
            while (index < source.length() && source.charAt(index) != quote) index++;
            selector.value = source.substring(start, index);
            if (index < source.length()) index++;
        } else {
            start = index;
            while (index < source.length() && source.charAt(index) != ']') index++;
            selector.value = source.substring(start, index).trim();
        }
        selector.value = Entities.decode(selector.value);
        return selector;
    }

    private static int skipWhitespace(String value, int index) {
        while (index < value.length() && Character.isWhitespace(value.charAt(index))) index++;
        return index;
    }

    private static boolean isIdentifier(char ch) {
        return Character.isLetterOrDigit(ch) || ch == '_' || ch == '-'
                || ch >= '\u0080';
    }

    private static List<String> splitTopLevel(String value, char separator) {
        List<String> parts = new ArrayList<>();
        int start = 0;
        int bracket = 0;
        int paren = 0;
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (ch == '[') bracket++;
            else if (ch == ']') bracket--;
            else if (ch == '(') paren++;
            else if (ch == ')') paren--;
            else if (ch == separator && bracket == 0 && paren == 0) {
                parts.add(value.substring(start, i));
                start = i + 1;
            }
        }
        parts.add(value.substring(start));
        return parts;
    }

    private static Elements ordered(Elements elements) {
        Set<Element> unique = new LinkedHashSet<>(elements);
        Elements out = new Elements();
        out.addAll(unique);
        return out;
    }

    private static final class CompoundSelector {
        String tag = "";
        String id = "";
        final List<String> classes = new ArrayList<>();
        final List<AttributeSelector> attributes = new ArrayList<>();
        final List<PseudoSelector> pseudos = new ArrayList<>();
        boolean child;
    }

    private static final class AttributeSelector {
        String name;
        String operator = "";
        String value = "";
    }

    private static final class PseudoSelector {
        final String name;
        final String argument;

        PseudoSelector(String name, String argument) {
            this.name = name;
            this.argument = argument == null ? "" : argument;
        }
    }
}
