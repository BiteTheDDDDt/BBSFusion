package dev.bbsfusion.site;

import dev.bbsfusion.core.Poll;

import org.jsoup.nodes.Element;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

final class PollParsers {
    private static final String FORMS = "form#poll, form[name=poll]";
    private static final String LABELS = "label[for^=option_]";

    private PollParsers() {
    }

    static List<Poll> discuzPolls(Element scope, String question) {
        List<Poll> polls = new ArrayList<>();
        if (scope == null) {
            return polls;
        }
        for (Element original : scope.select(FORMS)) {
            if (insideQuote(original) || hiddenByAncestor(original)) {
                continue;
            }
            Element form = original.clone();
            removeHiddenContent(form);
            List<Poll.Option> options = new ArrayList<>();
            Set<Element> optionBlocks = Collections.newSetFromMap(new IdentityHashMap<>());
            // Discuz desktop image polls use .polltd captions instead of labelled text rows.
            for (Element imageOption : form.select(".polltd")) {
                Element caption = imageOption.selectFirst("p.mtn, p.xi2");
                if (caption == null) {
                    continue;
                }
                String text = readableText(caption);
                if (text.isEmpty()) {
                    continue;
                }
                Element result = imageOption.selectFirst(".imgfc");
                String resultText = readableText(result);
                if (result != null && result.childrenSize() > 1) {
                    List<String> parts = new ArrayList<>();
                    for (Element part : result.children()) {
                        String value = readableText(part);
                        if (!value.isEmpty()) {
                            parts.add(value);
                        }
                    }
                    resultText = String.join(" ", parts);
                }
                options.add(new Poll.Option(text, resultText));
                optionBlocks.add(imageOption);
            }
            if (options.isEmpty()) {
                for (Element label : form.select(LABELS)) {
                    String text = readableText(label).replaceFirst("^\\s*\\d+[.、．]\\s*", "");
                    if (text.isEmpty()) {
                        continue;
                    }
                    Element row = closest(label, "tr", form);
                    Element block = row == null ? closest(label, "p", form) : row;
                    if (block == null) {
                        block = label.parent();
                    }
                    String result = "";
                    if (block != null && row == null) {
                        Element copy = block.clone();
                        copy.select(LABELS).remove();
                        result = readableText(copy);
                    } else if (row != null) {
                        Element resultRow = row.nextElementSibling();
                        // Desktop percentages/votes are in the next row, only when rendered.
                        if (resultRow != null && resultRow.selectFirst(".pbg, .pbr") != null
                                && resultRow.selectFirst(LABELS) == null) {
                            result = readableText(resultRow);
                            optionBlocks.add(resultRow);
                        }
                    }
                    options.add(new Poll.Option(text, result));
                    if (block != null) {
                        optionBlocks.add(block);
                    }
                }
            }
            if (options.isEmpty()) {
                continue;
            }
            for (Element block : optionBlocks) {
                block.remove();
            }
            polls.add(new Poll(question, readableText(form), options));
        }
        return polls;
    }

    /** Covers a content-only fallback without assigning one thread's poll to every reply. */
    static List<Poll> discuzPollsBesideContent(Element content, String question) {
        List<Poll> polls = discuzPolls(content, question);
        if (!polls.isEmpty()) {
            return polls;
        }
        Element sibling = content.nextElementSibling();
        while (sibling != null && "script".equals(sibling.normalName())) {
            sibling = sibling.nextElementSibling();
        }
        if (sibling != null && (sibling.is(FORMS) || sibling.hasClass("bt"))) {
            return discuzPolls(sibling, question);
        }
        return polls;
    }

    static void removeDiscuzPolls(Element content) {
        for (Element form : content.select(FORMS)) {
            // Keep readable fallback text if a site introduces an unknown poll layout.
            if (hiddenByAncestor(form) || !discuzPolls(form, "").isEmpty()) {
                form.remove();
            }
        }
    }

    private static Element closest(Element element, String tag, Element boundary) {
        for (Element cursor = element.parent(); cursor != null && cursor != boundary;
                cursor = cursor.parent()) {
            if (tag.equals(cursor.normalName())) {
                return cursor;
            }
        }
        return null;
    }

    private static boolean insideQuote(Element element) {
        for (Element cursor = element; cursor != null; cursor = cursor.parent()) {
            if ("blockquote".equals(cursor.normalName()) || cursor.hasClass("quote")
                    || cursor.hasClass("blockquote") || cursor.hasClass("reply-to")) {
                return true;
            }
        }
        return false;
    }

    private static boolean hiddenByAncestor(Element element) {
        for (Element cursor = element; cursor != null; cursor = cursor.parent()) {
            if (cursor.hasAttr("hidden") || "true".equalsIgnoreCase(cursor.attr("aria-hidden"))
                    || hiddenStyle(cursor)) {
                return true;
            }
        }
        return false;
    }

    private static String readableText(Element element) {
        if (element == null) {
            return "";
        }
        Element copy = element.clone();
        removeHiddenContent(copy);
        copy.select("input, button, select, textarea, script, style").remove();
        return HtmlText.cleanInline(copy.text());
    }

    private static void removeHiddenContent(Element element) {
        element.select("[hidden], [aria-hidden=true], script, style, input[type=hidden]").remove();
        for (Element child : element.select("[style]")) {
            if (hiddenStyle(child)) {
                child.remove();
            }
        }
    }

    private static boolean hiddenStyle(Element element) {
        String style = element.attr("style").toLowerCase(java.util.Locale.ROOT).replaceAll("\\s+", "");
        return style.contains("display:none") || style.contains("visibility:hidden");
    }
}
