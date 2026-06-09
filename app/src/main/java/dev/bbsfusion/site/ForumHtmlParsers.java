package dev.bbsfusion.site;

import dev.bbsfusion.core.BoardDefinition;
import dev.bbsfusion.core.Post;
import dev.bbsfusion.core.TopicDetail;
import dev.bbsfusion.core.TopicSummary;

import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.TextNode;
import org.jsoup.select.Elements;

import java.net.URI;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class ForumHtmlParsers {
    private static final ZoneId FORUM_ZONE = ZoneId.of("Asia/Shanghai");
    private static final Pattern S1_THREAD_ID = Pattern.compile("(?:tid=|thread-)(\\d+)");
    private static final Pattern NGA_THREAD_ID = Pattern.compile("tid=(\\d+)");
    private static final Pattern S1_BOARD_ID = Pattern.compile("(?:fid=|forum-)(-?\\d+)");
    private static final Pattern NGA_BOARD_ID = Pattern.compile("(fid|stid)=(-?\\d+)");
    private static final Pattern FULL_TIME = Pattern.compile(
            "(20\\d{2})[-/](\\d{1,2})[-/](\\d{1,2})\\s+(\\d{1,2}):(\\d{2})(?::\\d{2})?"
    );
    private static final Pattern MONTH_DAY_TIME = Pattern.compile(
            "(\\d{1,2})[-/](\\d{1,2})\\s+(\\d{1,2}):(\\d{2})(?::\\d{2})?"
    );
    private static final Pattern CHINESE_DAY_TIME = Pattern.compile("(今天|昨天|前天)\\s*(\\d{1,2}):(\\d{2})");
    private static final Pattern RELATIVE_TIME = Pattern.compile("(\\d+)\\s*(秒|分钟|小时|天)前");

    private ForumHtmlParsers() {
    }

    public static List<TopicSummary> extractTopics(
            Document document,
            String siteId,
            String baseUrl,
            String sourceLabel
    ) {
        if ("s1".equals(siteId)) {
            List<TopicSummary> s1Topics = extractS1DesktopTopics(document, siteId, baseUrl, sourceLabel);
            if (!s1Topics.isEmpty()) {
                return s1Topics;
            }
        }

        List<TopicSummary> topics = new ArrayList<>();
        Set<String> seen = new HashSet<>();

        for (Element anchor : document.select("a[href]")) {
            String href = anchor.attr("href");
            String text = clean(anchor.text());
            if (text.length() < 4 || isNavigationText(text)) {
                continue;
            }

            String absoluteUrl = absoluteUrl(anchor, href, baseUrl);
            if (!isTopicUrl(siteId, href, absoluteUrl)) {
                continue;
            }

            String key = topicKey(siteId, absoluteUrl);
            if (key.length() == 0 || !seen.add(key)) {
                continue;
            }

            String timeText = extractNearbyTimeText(anchor);
            long sortTimeMillis = parseTimeMillis(timeText);
            String meta = metaWithTime(sourceLabel, timeText);
            topics.add(new TopicSummary(siteId, text, absoluteUrl, meta, sortTimeMillis));
            if (topics.size() >= 80) {
                break;
            }
        }

        return topics;
    }

    private static List<TopicSummary> extractS1DesktopTopics(
            Document document,
            String siteId,
            String baseUrl,
            String sourceLabel
    ) {
        List<TopicSummary> topics = new ArrayList<>();
        Set<String> seen = new HashSet<>();

        for (Element row : document.select("tbody[id^=normalthread_]")) {
            Element titleAnchor = row.selectFirst("th a.xst[href]");
            if (titleAnchor == null) {
                continue;
            }

            String title = clean(titleAnchor.text());
            if (title.length() < 4 || isNavigationText(title)) {
                continue;
            }

            Element typeAnchor = row.selectFirst("th em a");
            if (typeAnchor != null) {
                String type = clean(typeAnchor.text());
                if (!type.isEmpty()) {
                    title = "[" + type + "] " + title;
                }
            }

            String absoluteUrl = absoluteUrl(titleAnchor, titleAnchor.attr("href"), baseUrl);
            String key = topicKey(siteId, absoluteUrl);
            if (key.length() == 0 || !seen.add(key)) {
                continue;
            }

            Elements byCells = row.select("td.by");
            String lastPostTime = "";
            if (!byCells.isEmpty()) {
                Element lastCell = byCells.last();
                Element timeElement = lastCell.selectFirst("em a, em span");
                if (timeElement == null) {
                    timeElement = lastCell.selectFirst("em");
                }
                if (timeElement != null) {
                    lastPostTime = clean(timeElement.text());
                }
            }
            long sortTimeMillis = parseTimeMillis(lastPostTime);
            topics.add(new TopicSummary(
                    siteId,
                    title,
                    absoluteUrl,
                    metaWithTime(sourceLabel, lastPostTime),
                    sortTimeMillis
            ));
            if (topics.size() >= 80) {
                break;
            }
        }

        return topics;
    }

    public static TopicDetail extractTopic(Document document, String url) {
        String title = extractTitle(document);
        List<Post> posts = extractPosts(document);
        if (posts.isEmpty()) {
            String body = clean(document.body() == null ? "" : document.body().text());
            if (!body.isEmpty()) {
                posts.add(new Post("页面正文", body));
            }
        }
        return new TopicDetail(title, url, posts);
    }

    public static List<BoardDefinition> extractBoards(
            Document document,
            String siteId,
            String baseUrl,
            String siteName
    ) {
        List<BoardDefinition> boards = new ArrayList<>();
        Set<String> seen = new HashSet<>();

        for (Element anchor : document.select("a[href]")) {
            String href = anchor.attr("href");
            String text = cleanBoardTitle(anchor.text());
            if (text.length() < 2 || text.length() > 32 || isNavigationText(text)) {
                continue;
            }

            String absoluteUrl = absoluteUrl(anchor, href, baseUrl);
            String boardId = boardId(siteId, href + " " + absoluteUrl);
            if (boardId.isEmpty() || !seen.add(siteId + ":" + boardId)) {
                continue;
            }

            String referrer = "s1".equals(siteId) ? "https://stage1st.com/2b/" : "https://bbs.nga.cn/";
            boards.add(new BoardDefinition(
                    siteId,
                    boardId,
                    text,
                    absoluteUrl,
                    referrer,
                    siteName + " " + text
            ));
        }

        return boards;
    }

    private static List<Post> extractPosts(Document document) {
        List<Post> posts = new ArrayList<>();
        Set<String> seen = new HashSet<>();

        addStructuredPosts(posts, seen, document.select("div[id^=post_], table[id^=pid], .plc[id^=pid]"));
        addStructuredPosts(posts, seen, document.select(".postlist > div, .postrow, .post"));
        if (!posts.isEmpty()) {
            return posts;
        }

        String[] selectors = {
                ".t_f",
                "td.t_f",
                "[id^=postmessage_]",
                ".postmessage",
                ".message",
                ".postcontent",
                "[id^=postcontent]",
                ".post_content"
        };

        for (String selector : selectors) {
            Elements elements = document.select(selector);
            for (Element element : elements) {
                ParsedPostContent content = parsedPostContent(element);
                String text = content.text;
                List<String> imageUrls = extractPostImages(element);
                if (text.length() < 12 && imageUrls.isEmpty()) {
                    continue;
                }
                String seenKey = text + " " + imageUrls;
                if (!seen.add(seenKey)) {
                    continue;
                }
                posts.add(new Post(
                        fallbackAuthor(posts.size()),
                        "",
                        "",
                        content.replyContext,
                        text,
                        imageUrls,
                        content.inlineImages
                ));
                if (posts.size() >= 40) {
                    return posts;
                }
            }
            if (!posts.isEmpty()) {
                return posts;
            }
        }

        for (Element paragraph : document.select("article, p, div")) {
            String text = clean(paragraph.text());
            if (text.length() < 40 || !seen.add(text)) {
                continue;
            }
            posts.add(new Post(fallbackAuthor(posts.size()), text));
            if (posts.size() >= 20) {
                break;
            }
        }

        return posts;
    }

    private static void addStructuredPosts(
            List<Post> posts,
            Set<String> seen,
            Elements containers
    ) {
        for (Element container : containers) {
            Element contentElement = firstElement(container, new String[]{
                    "td.t_f",
                    "[id^=postmessage_]",
                    ".postmessage",
                    ".message",
                    ".postcontent",
                    "[id^=postcontent]",
                    ".post_content",
                    ".content"
            });
            if (contentElement == null) {
                continue;
            }

            ParsedPostContent parsedContent = parsedPostContent(contentElement);
            String content = parsedContent.text;
            List<String> imageUrls = extractPostImages(contentElement);
            if (content.length() < 12 && imageUrls.isEmpty()) {
                continue;
            }
            String seenKey = content + " " + imageUrls;
            if (!seen.add(seenKey)) {
                continue;
            }

            String author = extractPostAuthor(container);
            if (author.isEmpty()) {
                author = fallbackAuthor(posts.size());
            }
            String avatarUrl = extractPostAvatar(container);
            String meta = extractPostMeta(container);
            posts.add(new Post(
                    author,
                    avatarUrl,
                    meta,
                    parsedContent.replyContext,
                    content,
                    imageUrls,
                    parsedContent.inlineImages
            ));
            if (posts.size() >= 40) {
                return;
            }
        }
    }

    private static String extractPostAuthor(Element container) {
        Element authorElement = firstElement(container, new String[]{
                ".authi a.xw1",
                ".authi .z a",
                ".authi a",
                ".authi a[href*=space-uid]",
                ".authi a[href*=space-username]",
                "a[href*=home.php][href*=mod=space]",
                ".pls a.xw1",
                ".pls a[href*=space-uid]",
                ".pls a[href*=space-username]",
                "a[href*=space-uid]",
                "a[href*=space-username]",
                ".poster a",
                ".poster",
                ".author a",
                ".author",
                ".username a",
                ".username",
                "[class*=author] a",
                "[class*=author]"
        });
        if (authorElement == null) {
            return "";
        }
        String author = clean(authorElement.text());
        if (author.length() > 40) {
            return "";
        }
        return author;
    }

    private static String extractPostAvatar(Element container) {
        Element image = firstElement(container, new String[]{
                ".avatar img[src]",
                ".avatar img[data-src]",
                ".avatar img[data-original]",
                ".avt img[src]",
                ".pls .avatar img[src]",
                ".useravatar img[src]",
                "[class*=avatar] img[src]",
                "img[src*=avatar]"
        });
        if (image == null) {
            return "";
        }

        String url = absoluteUrl(image, "src");
        if (url.isEmpty()) {
            url = absoluteUrl(image, "data-src");
        }
        if (url.isEmpty()) {
            url = absoluteUrl(image, "data-original");
        }
        return url;
    }

    private static String extractPostMeta(Element container) {
        String posted = extractPostTime(container);
        String edited = extractEditTime(container);
        if (posted.isEmpty()) {
            return edited;
        }
        if (edited.isEmpty() || posted.equals(edited)) {
            return posted;
        }
        return posted + " · " + edited;
    }

    private static String extractPostTime(Element container) {
        Element timeElement = firstElement(container, new String[]{
                "time[datetime]",
                ".authi em",
                ".pti .authi em",
                ".pi .authi em",
                ".authi span[title]",
                ".posttime",
                ".time"
        });
        if (timeElement != null) {
            String datetime = clean(timeElement.attr("datetime"));
            if (!datetime.isEmpty()) {
                return "发表于 " + datetime.replace('T', ' ').replaceAll("\\.\\d+Z?$", "");
            }
            String title = clean(timeElement.attr("title"));
            if (!title.isEmpty()) {
                return "发表于 " + title;
            }
            String text = cleanPostTimeLabel(timeElement.text());
            if (!text.isEmpty()) {
                return text;
            }
        }

        String time = extractLastTimeText(clean(container.select(".authi, .pti, .pi").text()));
        if (time.isEmpty()) {
            return "";
        }
        return "发表于 " + time;
    }

    private static String extractEditTime(Element container) {
        String text = clean(container.select(".pstatus, .editp, .post-edited").text());
        if (text.isEmpty()) {
            return "";
        }
        String time = extractLastTimeText(text);
        if (time.isEmpty()) {
            return text.length() > 50 ? "" : text;
        }
        return "编辑 " + time;
    }

    private static List<String> extractPostImages(Element contentElement) {
        List<String> imageUrls = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Element image : contentElement.select("img")) {
            if (isInlineEmoticon(image) || isInReplyBlock(image)) {
                continue;
            }
            String url = firstImageUrl(image);
            if (url.isEmpty() || isDecorativeImage(url) || !seen.add(url)) {
                continue;
            }
            imageUrls.add(url);
            if (imageUrls.size() >= 12) {
                break;
            }
        }
        return imageUrls;
    }

    private static String contentText(Element contentElement) {
        return parsedPostContent(contentElement).text;
    }

    private static ParsedPostContent parsedPostContent(Element contentElement) {
        Element copy = contentElement.clone();
        String replyContext = extractReplyContext(copy);
        copy.select(".quote, blockquote, .blockquote, .reply-to").remove();
        List<Post.InlineImage> inlineImages = new ArrayList<>();
        for (Element image : copy.select("img")) {
            String label = inlineImageLabel(image);
            if (label.isEmpty()) {
                image.remove();
            } else {
                String sourceUrl = firstImageUrl(image);
                if (!sourceUrl.isEmpty()) {
                    inlineImages.add(new Post.InlineImage(sourceUrl, label));
                }
                image.replaceWith(new TextNode(" " + label + " "));
            }
        }
        return new ParsedPostContent(clean(copy.text()), replyContext, inlineImages);
    }

    private static String extractReplyContext(Element contentElement) {
        Element quote = contentElement.selectFirst(".quote blockquote, .quote, blockquote, .blockquote, .reply-to");
        if (quote == null) {
            return "";
        }
        String text = clean(quote.text());
        if (text.isEmpty()) {
            return "";
        }
        return "引用：" + abbreviate(text, 120);
    }

    private static boolean isInReplyBlock(Element element) {
        Element cursor = element;
        while (cursor != null) {
            String className = cursor.className().toLowerCase(Locale.ROOT);
            if ("blockquote".equals(cursor.tagName())
                    || className.contains("quote")
                    || className.contains("blockquote")
                    || className.contains("reply-to")) {
                return true;
            }
            cursor = cursor.parent();
        }
        return false;
    }

    private static String abbreviate(String text, int maxLength) {
        String value = clean(text);
        if (value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength).trim() + "...";
    }

    private static String firstImageUrl(Element image) {
        String[] attributes = {"zoomfile", "file", "data-original", "data-src", "src"};
        for (String attribute : attributes) {
            String url = absoluteUrl(image, attribute);
            if (!url.isEmpty()) {
                return url;
            }
        }
        return "";
    }

    private static boolean isDecorativeImage(String url) {
        String value = url.toLowerCase(Locale.ROOT);
        return value.startsWith("data:")
                || value.contains("/static/image/smiley/")
                || value.contains("/image/smiley/")
                || value.contains("/smiley/")
                || value.contains("/avatar/")
                || value.contains("_avatar_")
                || value.contains("common/none.gif")
                || value.contains("common/back.gif");
    }

    private static boolean isInlineEmoticon(Element image) {
        String value = (
                firstImageUrl(image)
                        + " " + image.attr("class")
                        + " " + image.attr("smilieid")
                        + " " + image.attr("data-code")
        ).toLowerCase(Locale.ROOT);
        return value.contains("/static/image/smiley/")
                || value.contains("/image/smiley/")
                || value.contains("/smiley/")
                || value.contains("emoticon")
                || value.contains("emoji")
                || value.contains("smilie");
    }

    private static String inlineImageLabel(Element image) {
        if (!isInlineEmoticon(image)) {
            return "";
        }
        String label = firstNonEmpty(
                image.attr("alt"),
                image.attr("title"),
                image.attr("data-code"),
                image.attr("aria-label")
        );
        if (label.isEmpty()) {
            return "[表情]";
        }
        return clean(label);
    }

    private static Element firstElement(Element root, String[] selectors) {
        for (String selector : selectors) {
            Element element = root.selectFirst(selector);
            if (element != null) {
                return element;
            }
        }
        return null;
    }

    private static String fallbackAuthor(int index) {
        return "楼层 " + (index + 1);
    }

    private static String extractTitle(Document document) {
        String[] selectors = {
                "#thread_subject",
                "h1",
                ".ts .xw1",
                ".subject",
                ".thread-title"
        };

        for (String selector : selectors) {
            Element element = document.selectFirst(selector);
            if (element != null) {
                String text = clean(element.text());
                if (!text.isEmpty()) {
                    return text;
                }
            }
        }

        String title = clean(document.title());
        return title.isEmpty() ? "帖子详情" : title;
    }

    private static boolean isTopicUrl(String siteId, String rawHref, String absoluteUrl) {
        String href = (rawHref + " " + absoluteUrl).toLowerCase(Locale.ROOT);
        if ("s1".equals(siteId)) {
            return href.contains("mod=viewthread")
                    || href.contains("thread-")
                    || href.contains("tid=");
        }
        if ("nga".equals(siteId)) {
            return href.contains("read.php?tid=")
                    || href.contains("read.php&tid=")
                    || href.contains("/read.php")
                    && href.contains("tid=");
        }
        return false;
    }

    private static String topicKey(String siteId, String absoluteUrl) {
        Pattern pattern = "s1".equals(siteId) ? S1_THREAD_ID : NGA_THREAD_ID;
        Matcher matcher = pattern.matcher(absoluteUrl);
        if (matcher.find()) {
            return siteId + ":" + matcher.group(1);
        }
        return siteId + ":" + absoluteUrl;
    }

    private static String boardId(String siteId, String href) {
        String value = href.toLowerCase(Locale.ROOT);
        Matcher matcher;
        if ("s1".equals(siteId)) {
            if (value.contains("mod=viewthread") || value.contains("thread-")) {
                return "";
            }
            matcher = S1_BOARD_ID.matcher(value);
        } else if ("nga".equals(siteId)) {
            if (value.contains("tid=") || !value.contains("thread.php")) {
                return "";
            }
            matcher = NGA_BOARD_ID.matcher(value);
        } else {
            return "";
        }
        if (!matcher.find()) {
            return "";
        }
        if ("nga".equals(siteId) && "stid".equals(matcher.group(1))) {
            return "stid:" + matcher.group(2);
        }
        return "nga".equals(siteId) ? matcher.group(2) : matcher.group(1);
    }

    private static String absoluteUrl(Element anchor, String href, String baseUrl) {
        String abs = anchor.absUrl("href");
        if (!abs.isEmpty()) {
            return abs;
        }
        try {
            return URI.create(baseUrl).resolve(href).toString();
        } catch (IllegalArgumentException ignored) {
            return href;
        }
    }

    private static String absoluteUrl(Element element, String attribute) {
        String abs = element.absUrl(attribute);
        if (!abs.isEmpty()) {
            return abs;
        }
        String raw = element.attr(attribute).trim();
        if (raw.isEmpty()) {
            return "";
        }
        if (raw.startsWith("//")) {
            return "https:" + raw;
        }
        String baseUri = element.baseUri();
        if (baseUri == null || baseUri.isEmpty()) {
            return raw;
        }
        try {
            return URI.create(baseUri).resolve(raw).toString();
        } catch (IllegalArgumentException ignored) {
            return raw;
        }
    }

    private static String extractNearbyTimeText(Element anchor) {
        Element cursor = anchor;
        for (int i = 0; i < 5 && cursor != null; i++) {
            String time = extractLastTimeText(clean(cursor.text()));
            if (!time.isEmpty()) {
                return time;
            }
            cursor = cursor.parent();
        }
        return "";
    }

    private static String extractLastTimeText(String text) {
        String found = "";
        Matcher full = FULL_TIME.matcher(text);
        while (full.find()) {
            found = full.group();
        }
        if (!found.isEmpty()) {
            return found;
        }

        Matcher chineseDay = CHINESE_DAY_TIME.matcher(text);
        while (chineseDay.find()) {
            found = chineseDay.group();
        }
        if (!found.isEmpty()) {
            return found;
        }

        Matcher relative = RELATIVE_TIME.matcher(text);
        while (relative.find()) {
            found = relative.group();
        }
        if (!found.isEmpty()) {
            return found;
        }

        Matcher monthDay = MONTH_DAY_TIME.matcher(text);
        while (monthDay.find()) {
            found = monthDay.group();
        }
        return found;
    }

    static long parseTimeMillis(String text) {
        String value = clean(text);
        if (value.isEmpty()) {
            return 0L;
        }

        ZoneId zone = FORUM_ZONE;
        Matcher full = FULL_TIME.matcher(value);
        if (full.find()) {
            return millis(
                    Integer.parseInt(full.group(1)),
                    Integer.parseInt(full.group(2)),
                    Integer.parseInt(full.group(3)),
                    Integer.parseInt(full.group(4)),
                    Integer.parseInt(full.group(5)),
                    zone
            );
        }

        Matcher chineseDay = CHINESE_DAY_TIME.matcher(value);
        if (chineseDay.find()) {
            LocalDate date = LocalDate.now(zone);
            String day = chineseDay.group(1);
            if ("昨天".equals(day)) {
                date = date.minusDays(1);
            } else if ("前天".equals(day)) {
                date = date.minusDays(2);
            }
            LocalTime time = LocalTime.of(
                    Integer.parseInt(chineseDay.group(2)),
                    Integer.parseInt(chineseDay.group(3))
            );
            return LocalDateTime.of(date, time).atZone(zone).toInstant().toEpochMilli();
        }

        Matcher relative = RELATIVE_TIME.matcher(value);
        if (relative.find()) {
            long amount = Long.parseLong(relative.group(1));
            String unit = relative.group(2);
            long millis = 1000L;
            if ("分钟".equals(unit)) {
                millis = 60_000L;
            } else if ("小时".equals(unit)) {
                millis = 3_600_000L;
            } else if ("天".equals(unit)) {
                millis = 86_400_000L;
            }
            return System.currentTimeMillis() - amount * millis;
        }

        Matcher monthDay = MONTH_DAY_TIME.matcher(value);
        if (monthDay.find()) {
            LocalDate now = LocalDate.now(zone);
            return millis(
                    now.getYear(),
                    Integer.parseInt(monthDay.group(1)),
                    Integer.parseInt(monthDay.group(2)),
                    Integer.parseInt(monthDay.group(3)),
                    Integer.parseInt(monthDay.group(4)),
                    zone
            );
        }

        return 0L;
    }

    private static long millis(int year, int month, int day, int hour, int minute, ZoneId zone) {
        return LocalDateTime.of(year, month, day, hour, minute)
                .atZone(zone)
                .toInstant()
                .toEpochMilli();
    }

    private static String metaWithTime(String sourceLabel, String timeText) {
        String time = clean(timeText);
        if (time.isEmpty()) {
            return sourceLabel;
        }
        return sourceLabel + " · " + time;
    }

    private static boolean isNavigationText(String text) {
        String value = text.trim();
        return "上一页".equals(value)
                || "下一页".equals(value)
                || "返回".equals(value)
                || "回复".equals(value)
                || "发表回复".equals(value)
                || "只看该作者".equals(value);
    }

    private static String cleanBoardTitle(String text) {
        String value = clean(text);
        return value.replaceAll("\\s*帖数:\\s*.*$", "")
                .replaceAll("\\s*今日:\\s*.*$", "")
                .trim();
    }

    private static String clean(String text) {
        if (text == null) {
            return "";
        }
        return text.replace('\u00a0', ' ')
                .replaceAll("\\s+", " ")
                .trim();
    }

    private static String firstNonEmpty(String... values) {
        for (String value : values) {
            if (value != null && !value.trim().isEmpty()) {
                return value.trim();
            }
        }
        return "";
    }

    private static String cleanPostTimeLabel(String text) {
        String value = clean(text);
        if (value.isEmpty()) {
            return "";
        }
        value = value.replaceFirst("^发表于\\s*", "发表于 ");
        if (value.startsWith("发表于 ")) {
            return value;
        }
        String time = extractLastTimeText(value);
        if (!time.isEmpty()) {
            return "发表于 " + time;
        }
        return value.length() > 40 ? "" : value;
    }

    private static final class ParsedPostContent {
        final String text;
        final String replyContext;
        final List<Post.InlineImage> inlineImages;

        ParsedPostContent(String text, String replyContext, List<Post.InlineImage> inlineImages) {
            this.text = text;
            this.replyContext = replyContext;
            this.inlineImages = inlineImages;
        }
    }
}
