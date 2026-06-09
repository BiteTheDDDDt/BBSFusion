package dev.bbsfusion.site;

import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;

final class HtmlText {
    private HtmlText() {
    }

    static String textWithLineBreaks(Element root) {
        if (root == null) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        appendText(root, builder, true);
        return cleanMultiline(builder.toString());
    }

    static String cleanInline(String text) {
        if (text == null) {
            return "";
        }
        return text.replace('\u00a0', ' ').replaceAll("\\s+", " ").trim();
    }

    private static void appendText(Node node, StringBuilder builder, boolean root) {
        if (node instanceof TextNode) {
            builder.append(((TextNode) node).getWholeText());
            return;
        }
        if (!(node instanceof Element)) {
            return;
        }

        Element element = (Element) node;
        String tag = element.normalName();
        if ("br".equals(tag)) {
            appendLineBreak(builder);
            return;
        }

        boolean block = !root && isBlockTag(tag);
        if (block) {
            appendLineBreak(builder);
        }
        for (Node child : element.childNodes()) {
            appendText(child, builder, false);
        }
        if (block) {
            appendLineBreak(builder);
        }
    }

    private static boolean isBlockTag(String tag) {
        return "p".equals(tag)
                || "div".equals(tag)
                || "section".equals(tag)
                || "article".equals(tag)
                || "blockquote".equals(tag)
                || "aside".equals(tag)
                || "ul".equals(tag)
                || "ol".equals(tag)
                || "li".equals(tag)
                || "table".equals(tag)
                || "thead".equals(tag)
                || "tbody".equals(tag)
                || "tfoot".equals(tag)
                || "tr".equals(tag)
                || "td".equals(tag)
                || "th".equals(tag)
                || "pre".equals(tag)
                || tag.matches("h[1-6]");
    }

    private static void appendLineBreak(StringBuilder builder) {
        int length = builder.length();
        if (length == 0 || builder.charAt(length - 1) == '\n') {
            return;
        }
        builder.append('\n');
    }

    private static String cleanMultiline(String text) {
        if (text == null) {
            return "";
        }
        return text.replace('\u00a0', ' ')
                .replace("\r\n", "\n")
                .replace('\r', '\n')
                .replaceAll("[\\t\\x0B\\f ]+", " ")
                .replaceAll(" *\\n *", "\n")
                .replaceAll("\\n{2,}", "\n")
                .trim();
    }
}
