package dev.bbsfusion.site;

import dev.bbsfusion.core.BoardCatalog;
import dev.bbsfusion.core.BoardDefinition;
import dev.bbsfusion.core.ForumConnector;
import dev.bbsfusion.core.Post;
import dev.bbsfusion.core.TopicDetail;
import dev.bbsfusion.core.TopicSummary;

import org.json.JSONArray;
import org.json.JSONObject;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import java.io.IOException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class LinuxDoConnector implements ForumConnector {
    private static final String HOME_URL = "https://linux.do/latest";
    private static final String BASE_URL = "https://linux.do";
    private static final String LOGIN_URL = "https://linux.do/login";
    private static final String CATEGORIES_URL =
            "https://linux.do/categories.json?include_subcategories=true";
    private static final Pattern CATEGORY_ID = Pattern.compile("^c:([^:]+):(\\d+)$");
    private static final Pattern TOPIC_ID = Pattern.compile("/t/(?:[^/]+/)?(\\d+)");
    private static final DateTimeFormatter TIME_FORMATTER =
            DateTimeFormatter.ofPattern("M-d HH:mm").withZone(ZoneId.of("Asia/Shanghai"));

    @Override
    public String id() {
        return "linuxdo";
    }

    @Override
    public String name() {
        return "Linux.do";
    }

    @Override
    public String homeUrl() {
        return HOME_URL;
    }

    @Override
    public String loginUrl() {
        return LOGIN_URL;
    }

    @Override
    public List<TopicSummary> fetchTopics() throws IOException {
        return fetchTopics(BoardCatalog.defaultBoardForSite(id()));
    }

    @Override
    public List<TopicSummary> fetchTopics(BoardDefinition board) throws IOException {
        IOException jsonError = null;
        try {
            List<TopicSummary> topics = parseTopicsFromJson(
                    NetworkClient.getJsonObject(jsonUrlForBoard(board), board.referrer),
                    board
            );
            if (!topics.isEmpty()) {
                return topics;
            }
        } catch (IOException error) {
            jsonError = error;
        }

        try {
            Document document = NetworkClient.getDesktop(board.url, board.referrer);
            List<TopicSummary> topics = parseTopicsFromHtml(document, board);
            if (!topics.isEmpty()) {
                return topics;
            }
        } catch (IOException htmlError) {
            if (jsonError != null) {
                throw jsonError;
            }
            throw htmlError;
        }

        return new ArrayList<>();
    }

    @Override
    public List<BoardDefinition> fetchAvailableBoards() throws IOException {
        List<BoardDefinition> boards = new ArrayList<>();
        for (BoardDefinition board : BoardCatalog.builtInBoards()) {
            if (id().equals(board.siteId)) {
                boards.add(board);
            }
        }

        try {
            boards = BoardCatalog.merge(
                    boards,
                    parseBoardsFromJson(NetworkClient.getJsonObject(CATEGORIES_URL, HOME_URL))
            );
        } catch (IOException ignored) {
            try {
                Document document = NetworkClient.getDesktop("https://linux.do/categories?tl=en", HOME_URL);
                boards = BoardCatalog.merge(boards, parseBoardsFromHtml(document));
            } catch (IOException ignoredAgain) {
                return boards;
            }
        }
        return boards;
    }

    @Override
    public TopicDetail fetchTopic(String url) throws IOException {
        String topicId = topicIdFromUrl(url);
        IOException jsonError = null;
        if (!topicId.isEmpty()) {
            try {
                TopicDetail detail = parseTopicFromJson(
                        NetworkClient.getJsonObject(BASE_URL + "/t/" + topicId + ".json", HOME_URL),
                        url
                );
                if (!detail.posts.isEmpty()) {
                    return detail;
                }
            } catch (IOException error) {
                jsonError = error;
            }
        }

        try {
            Document document = NetworkClient.getDesktop(url, HOME_URL);
            return parseTopicFromHtml(document, url);
        } catch (IOException htmlError) {
            if (jsonError != null) {
                throw jsonError;
            }
            throw htmlError;
        }
    }

    static List<TopicSummary> parseTopicsFromJson(JSONObject root, BoardDefinition board) {
        List<TopicSummary> topics = new ArrayList<>();
        JSONObject topicList = root.optJSONObject("topic_list");
        JSONArray array = topicList == null ? null : topicList.optJSONArray("topics");
        if (array == null) {
            return topics;
        }

        Map<Integer, String> categories = categoryNames(root.optJSONObject("category_list"));
        for (int i = 0; i < array.length() && topics.size() < 80; i++) {
            JSONObject item = array.optJSONObject(i);
            if (item == null) {
                continue;
            }
            long topicId = item.optLong("id", 0L);
            String title = item.optString("title", "").trim();
            if (topicId == 0L || title.length() < 2) {
                continue;
            }

            long sortTimeMillis = parseInstantMillis(firstNonEmpty(
                    item.optString("bumped_at", ""),
                    item.optString("last_posted_at", ""),
                    item.optString("created_at", "")
            ));
            String label = board.sourceLabel;
            if ("latest".equals(board.boardId)) {
                String categoryName = categories.get(item.optInt("category_id", -1));
                if (categoryName != null && !categoryName.isEmpty()) {
                    label = "Linux.do " + categoryName;
                }
            }

            String meta = label;
            if (sortTimeMillis > 0L) {
                meta += " · " + TIME_FORMATTER.format(Instant.ofEpochMilli(sortTimeMillis));
            }
            int replies = item.optInt("reply_count", item.optInt("posts_count", 1) - 1);
            if (replies >= 0) {
                meta += " · " + replies + " 回复";
            }

            String slug = item.optString("slug", "topic").trim();
            if (slug.isEmpty()) {
                slug = "topic";
            }
            topics.add(new TopicSummary(
                    "linuxdo",
                    title,
                    BASE_URL + "/t/" + slug + "/" + topicId,
                    meta,
                    sortTimeMillis
            ));
        }
        return topics;
    }

    static List<TopicSummary> parseTopicsFromHtml(Document document, BoardDefinition board) {
        List<TopicSummary> topics = new ArrayList<>();
        List<String> seen = new ArrayList<>();
        for (Element anchor : document.select("a[href]")) {
            String href = anchor.attr("href").trim();
            if (!isTopicHref(href)) {
                continue;
            }
            String title = clean(anchor.text());
            if (title.length() < 2 || title.equalsIgnoreCase("next page")) {
                continue;
            }
            String url = absoluteLinuxDoUrl(href);
            String topicId = topicIdFromUrl(url);
            if (topicId.isEmpty() || seen.contains(topicId)) {
                continue;
            }
            seen.add(topicId);
            topics.add(new TopicSummary("linuxdo", title, url, board.sourceLabel));
            if (topics.size() >= 80) {
                break;
            }
        }
        return topics;
    }

    static List<BoardDefinition> parseBoardsFromJson(JSONObject root) {
        List<BoardDefinition> boards = new ArrayList<>();
        JSONObject categoryList = root.optJSONObject("category_list");
        JSONArray categories = categoryList == null ? null : categoryList.optJSONArray("categories");
        if (categories != null) {
            collectCategories(categories, boards);
        }
        return boards;
    }

    static List<BoardDefinition> parseBoardsFromHtml(Document document) {
        List<BoardDefinition> boards = new ArrayList<>();
        List<String> seen = new ArrayList<>();
        for (Element anchor : document.select("a[href^=/c/], a[href^=https://linux.do/c/]")) {
            String href = anchor.attr("href").trim();
            String[] parts = categoryParts(href);
            if (parts == null || seen.contains(parts[1])) {
                continue;
            }
            String title = clean(anchor.text());
            if (title.length() < 2) {
                continue;
            }
            seen.add(parts[1]);
            boards.add(linuxDoBoard(parts[0], parts[1], title));
        }
        return boards;
    }

    static TopicDetail parseTopicFromJson(JSONObject root, String url) {
        String title = root.optString("title", "").trim();
        List<Post> posts = new ArrayList<>();
        JSONObject stream = root.optJSONObject("post_stream");
        JSONArray array = stream == null ? null : stream.optJSONArray("posts");
        if (array == null) {
            return new TopicDetail(title.isEmpty() ? "帖子详情" : title, url, posts);
        }

        for (int i = 0; i < array.length() && posts.size() < 80; i++) {
            JSONObject item = array.optJSONObject(i);
            if (item == null) {
                continue;
            }
            ParsedContent content = parsedCooked(item.optString("cooked", ""));
            if (content.text.isEmpty() && content.imageUrls.isEmpty()) {
                continue;
            }
            posts.add(new Post(
                    firstNonEmpty(
                            item.optString("display_username", ""),
                            item.optString("username", ""),
                            item.optString("name", ""),
                            "楼层 " + (posts.size() + 1)
                    ),
                    discourseAvatar(item.optString("avatar_template", "")),
                    content.text,
                    content.imageUrls
            ));
        }
        return new TopicDetail(title.isEmpty() ? "帖子详情" : title, url, posts);
    }

    static TopicDetail parseTopicFromHtml(Document document, String url) {
        String title = clean(firstText(document, "h1", "title"));
        List<Post> posts = new ArrayList<>();
        for (Element container : document.select("article, .topic-body, .crawler-post")) {
            Element contentElement = firstElement(container, ".cooked", ".post", ".topic-body", "div");
            if (contentElement == null) {
                contentElement = container;
            }
            ParsedContent content = parsedCooked(contentElement.html());
            if (content.text.length() < 2 && content.imageUrls.isEmpty()) {
                continue;
            }
            String author = clean(firstText(container, ".creator a", ".names a", "a[href^=/u/]"));
            if (author.isEmpty()) {
                author = "楼层 " + (posts.size() + 1);
            }
            String avatar = "";
            Element avatarElement = container.selectFirst("img.avatar[src], img[src*=user_avatar]");
            if (avatarElement != null) {
                avatar = absoluteLinuxDoUrl(avatarElement.attr("src"));
            }
            posts.add(new Post(author, avatar, content.text, content.imageUrls));
            if (posts.size() >= 80) {
                break;
            }
        }
        return new TopicDetail(title.isEmpty() ? "帖子详情" : title, url, posts);
    }

    static String topicIdFromUrl(String url) {
        if (url == null) {
            return "";
        }
        Matcher matcher = TOPIC_ID.matcher(url);
        return matcher.find() ? matcher.group(1) : "";
    }

    private static String jsonUrlForBoard(BoardDefinition board) {
        if ("latest".equals(board.boardId)) {
            return BASE_URL + "/latest.json";
        }
        Matcher matcher = CATEGORY_ID.matcher(board.boardId);
        if (matcher.matches()) {
            return BASE_URL + "/c/" + matcher.group(1) + "/" + matcher.group(2) + ".json";
        }
        String url = board.url.endsWith("/") ? board.url.substring(0, board.url.length() - 1) : board.url;
        return url + ".json";
    }

    private static void collectCategories(JSONArray categories, List<BoardDefinition> boards) {
        for (int i = 0; i < categories.length(); i++) {
            JSONObject category = categories.optJSONObject(i);
            if (category == null) {
                continue;
            }
            addCategory(category, boards);
            JSONArray subcategories = category.optJSONArray("subcategory_list");
            if (subcategories != null) {
                collectCategories(subcategories, boards);
            }
        }
    }

    private static void addCategory(JSONObject category, List<BoardDefinition> boards) {
        int categoryId = category.optInt("id", 0);
        String slug = category.optString("slug", "").trim();
        String name = category.optString("name", "").trim();
        if (categoryId <= 0 || slug.isEmpty() || name.isEmpty()) {
            return;
        }
        boards.add(linuxDoBoard(slug, String.valueOf(categoryId), name));
    }

    private static BoardDefinition linuxDoBoard(String slug, String categoryId, String title) {
        return new BoardDefinition(
                "linuxdo",
                "c:" + slug + ":" + categoryId,
                title,
                BASE_URL + "/c/" + slug + "/" + categoryId,
                BASE_URL + "/",
                "Linux.do " + title
        );
    }

    private static Map<Integer, String> categoryNames(JSONObject categoryList) {
        Map<Integer, String> names = new HashMap<>();
        JSONArray categories = categoryList == null ? null : categoryList.optJSONArray("categories");
        if (categories != null) {
            collectCategoryNames(categories, names);
        }
        return names;
    }

    private static void collectCategoryNames(JSONArray categories, Map<Integer, String> names) {
        for (int i = 0; i < categories.length(); i++) {
            JSONObject category = categories.optJSONObject(i);
            if (category == null) {
                continue;
            }
            int categoryId = category.optInt("id", 0);
            String name = category.optString("name", "").trim();
            if (categoryId > 0 && !name.isEmpty()) {
                names.put(categoryId, name);
            }
            JSONArray subcategories = category.optJSONArray("subcategory_list");
            if (subcategories != null) {
                collectCategoryNames(subcategories, names);
            }
        }
    }

    static ParsedContent parsedCooked(String html) {
        Document document = Jsoup.parse(html == null ? "" : html, BASE_URL + "/");
        List<String> imageUrls = new ArrayList<>();
        for (Element image : document.select("img[src]")) {
            String src = absoluteLinuxDoUrl(image.attr("src"));
            if (!src.isEmpty() && !imageUrls.contains(src)) {
                imageUrls.add(src);
            }
            image.remove();
        }
        String text = clean(document.text());
        return new ParsedContent(text, imageUrls);
    }

    private static String discourseAvatar(String template) {
        if (template == null) {
            return "";
        }
        return absoluteLinuxDoUrl(template.replace("{size}", "96"));
    }

    private static long parseInstantMillis(String value) {
        if (value == null || value.trim().isEmpty()) {
            return 0L;
        }
        try {
            return Instant.parse(value.trim()).toEpochMilli();
        } catch (Exception ignored) {
            return 0L;
        }
    }

    private static boolean isTopicHref(String href) {
        return href.startsWith("/t/") || href.startsWith("https://linux.do/t/");
    }

    private static String[] categoryParts(String href) {
        String value = href;
        int query = value.indexOf('?');
        if (query >= 0) {
            value = value.substring(0, query);
        }
        value = value.replace("https://linux.do", "");
        String[] parts = value.split("/");
        if (parts.length < 4 || !"c".equals(parts[1])) {
            return null;
        }
        return new String[]{parts[2], parts[3]};
    }

    private static String absoluteLinuxDoUrl(String url) {
        if (url == null) {
            return "";
        }
        String value = url.trim();
        if (value.isEmpty()) {
            return "";
        }
        if (value.startsWith("https://") || value.startsWith("http://")) {
            return value;
        }
        if (value.startsWith("//")) {
            return "https:" + value;
        }
        if (value.startsWith("/")) {
            return BASE_URL + value;
        }
        return value;
    }

    private static Element firstElement(Element root, String... selectors) {
        for (String selector : selectors) {
            Element element = root.selectFirst(selector);
            if (element != null) {
                return element;
            }
        }
        return null;
    }

    private static String firstText(Element root, String... selectors) {
        for (String selector : selectors) {
            Element element = root.selectFirst(selector);
            if (element != null) {
                return element.text();
            }
        }
        return "";
    }

    private static String clean(String text) {
        if (text == null) {
            return "";
        }
        return text.replace('\u00a0', ' ').replaceAll("\\s+", " ").trim();
    }

    private static String firstNonEmpty(String... values) {
        for (String value : values) {
            if (value != null && !value.trim().isEmpty()) {
                return value.trim();
            }
        }
        return "";
    }

    static final class ParsedContent {
        final String text;
        final List<String> imageUrls;

        ParsedContent(String text, List<String> imageUrls) {
            this.text = text;
            this.imageUrls = imageUrls;
        }
    }
}
