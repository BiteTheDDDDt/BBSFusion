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
import org.jsoup.nodes.TextNode;

import java.io.IOException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
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
    private static final Pattern RSS_POST_COUNT = Pattern.compile("(\\d+)\\s*个帖子");
    private static final int POSTS_PAGE_SIZE = 20;
    private static final DateTimeFormatter TIME_FORMATTER =
            DateTimeFormatter.ofPattern("M-d HH:mm").withZone(ZoneId.of("Asia/Shanghai"));
    private static final DateTimeFormatter POST_TIME_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-M-d HH:mm").withZone(ZoneId.of("Asia/Shanghai"));

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
            List<TopicSummary> topics = parseTopicsFromRss(
                    NetworkClient.getXml(rssUrlForBoard(board), board.referrer),
                    board
            );
            if (!topics.isEmpty()) {
                return topics;
            }
        } catch (IOException ignored) {
            // Fall through to crawler HTML below.
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
        return fetchTopicPage(url, 1);
    }

    @Override
    public TopicDetail fetchTopicPage(String url, int page) throws IOException {
        int pageNumber = Math.max(1, page);
        IOException firstError = null;
        for (String jsonUrl : jsonUrlsForTopic(url)) {
            try {
                TopicDetail detail = fetchTopicPageFromJson(jsonUrl, url, pageNumber);
                if (!detail.posts.isEmpty()) {
                    return detail;
                }
            } catch (IOException error) {
                if (firstError == null) {
                    firstError = error;
                }
            }
        }

        if (pageNumber > 1) {
            if (firstError != null) {
                throw firstError;
            }
            return new TopicDetail("帖子详情", url, new ArrayList<>(), pageNumber, false);
        }

        for (String rssUrl : rssUrlsForTopic(url)) {
            try {
                TopicDetail detail = parseTopicFromRss(NetworkClient.getXml(rssUrl, url), url);
                if (!detail.posts.isEmpty()) {
                    return detail;
                }
            } catch (IOException error) {
                if (firstError == null) {
                    firstError = error;
                }
            }
        }

        try {
            Document document = NetworkClient.getDesktop(url, HOME_URL);
            return parseTopicFromHtml(document, url);
        } catch (IOException htmlError) {
            if (firstError != null) {
                throw firstError;
            }
            throw htmlError;
        }
    }

    private TopicDetail fetchTopicPageFromJson(
            String jsonUrl,
            String topicUrl,
            int pageNumber
    ) throws IOException {
        JSONObject root = NetworkClient.getJsonObject(topicJsonRequestUrl(jsonUrl), topicUrl);
        if (pageNumber <= 1) {
            return parseTopicFromJson(root, topicUrl, 1);
        }

        JSONArray streamIds = streamIds(root);
        int start = (pageNumber - 1) * POSTS_PAGE_SIZE;
        if (streamIds.length() <= start) {
            return new TopicDetail(topicTitle(root), topicUrl, new ArrayList<>(), pageNumber, false);
        }

        List<String> postIds = new ArrayList<>();
        for (int i = start; i < streamIds.length() && postIds.size() < POSTS_PAGE_SIZE; i++) {
            String postId = String.valueOf(streamIds.optLong(i, 0L));
            if (!"0".equals(postId)) {
                postIds.add(postId);
            }
        }
        if (postIds.isEmpty()) {
            return new TopicDetail(topicTitle(root), topicUrl, new ArrayList<>(), pageNumber, false);
        }

        String topicId = firstNonEmpty(topicIdFromUrl(topicUrl), topicIdFromUrl(jsonUrl));
        String postsUrl = postsJsonUrlForTopic(topicId, postIds);
        if (postsUrl.isEmpty()) {
            return new TopicDetail(topicTitle(root), topicUrl, new ArrayList<>(), pageNumber, false);
        }
        JSONObject postsRoot = NetworkClient.getJsonObject(postsUrl, topicUrl);
        return parseTopicFromJson(
                postsRoot,
                topicUrl,
                pageNumber,
                start + postIds.size() < streamIds.length(),
                topicTitle(root)
        );
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

    static List<TopicSummary> parseTopicsFromRss(Document document, BoardDefinition board) {
        List<TopicSummary> topics = new ArrayList<>();
        for (Element item : document.select("item")) {
            String title = clean(firstText(item, "title"));
            String url = clean(firstText(item, "link"));
            if (title.length() < 2 || url.isEmpty()) {
                continue;
            }

            long sortTimeMillis = parseRssDateMillis(firstText(item, "pubDate"));
            String label = board.sourceLabel;
            String category = clean(firstText(item, "category"));
            if ("latest".equals(board.boardId) && !category.isEmpty()) {
                label = "Linux.do " + category;
            }

            String meta = label;
            if (sortTimeMillis > 0L) {
                meta += " · " + TIME_FORMATTER.format(Instant.ofEpochMilli(sortTimeMillis));
            }
            int replies = repliesFromRssDescription(firstText(item, "description"));
            if (replies >= 0) {
                meta += " · " + replies + " 回复";
            }

            topics.add(new TopicSummary("linuxdo", title, absoluteLinuxDoUrl(url), meta, sortTimeMillis));
            if (topics.size() >= 80) {
                break;
            }
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
        return parseTopicFromJson(root, url, 1);
    }

    static TopicDetail parseTopicFromJson(JSONObject root, String url, int pageNumber) {
        return parseTopicFromJson(
                root,
                url,
                pageNumber,
                streamIds(root).length() > pageNumber * POSTS_PAGE_SIZE,
                topicTitle(root)
        );
    }

    static TopicDetail parseTopicFromJson(
            JSONObject root,
            String url,
            int pageNumber,
            boolean hasMore,
            String titleFallback
    ) {
        String title = firstNonEmpty(topicTitle(root), titleFallback);
        List<Post> posts = new ArrayList<>();
        JSONObject stream = root.optJSONObject("post_stream");
        JSONArray array = stream == null ? null : stream.optJSONArray("posts");
        if (array == null) {
            return new TopicDetail(title.isEmpty() ? "帖子详情" : title, url, posts, pageNumber, false);
        }

        for (JsonPost jsonPost : sortedJsonPosts(array)) {
            Post post = postFromJson(jsonPost.item, posts.size() + 1);
            if (post != null) {
                posts.add(post);
            }
            if (posts.size() >= 80) {
                break;
            }
        }
        return new TopicDetail(title.isEmpty() ? "帖子详情" : title, url, posts, pageNumber, hasMore);
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
            posts.add(new Post(
                    author,
                    avatar,
                    postMetaFromHtml(container),
                    content.replyContext,
                    content.text,
                    content.imageUrls,
                    content.inlineImages
            ));
            if (posts.size() >= 80) {
                break;
            }
        }
        return new TopicDetail(title.isEmpty() ? "帖子详情" : title, url, posts);
    }

    static TopicDetail parseTopicFromRss(Document document, String url) {
        String title = clean(firstText(document, "channel > title", "title"));
        List<Post> posts = new ArrayList<>();
        List<String> seenContent = new ArrayList<>();

        ParsedContent topicDescription = parsedRssDescription(
                firstText(document, "channel > description"),
                url
        );
        addRssPostIfPresent(posts, seenContent, new Post(
                "主题",
                "",
                "",
                topicDescription.replyContext,
                topicDescription.text,
                topicDescription.imageUrls,
                topicDescription.inlineImages
        ));

        for (Element item : sortedRssItems(document)) {
            ParsedContent content = parsedRssDescription(firstText(item, "description"), url);
            if (content.text.isEmpty() && content.imageUrls.isEmpty()) {
                continue;
            }
            String author = firstNonEmpty(
                    firstText(item, "dc\\:creator", "dc|creator", "creator", "author"),
                    "楼层 " + (posts.size() + 1)
            );
            String meta = postMetaFromRssDate(firstText(item, "pubDate"));
            addRssPostIfPresent(posts, seenContent, new Post(
                    author,
                    "",
                    meta,
                    content.replyContext,
                    content.text,
                    content.imageUrls,
                    content.inlineImages
            ));
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

    static List<String> jsonUrlsForTopic(String url) {
        List<String> urls = new ArrayList<>();
        String primary = jsonUrlForTopic(url);
        if (!primary.isEmpty()) {
            urls.add(primary);
        }

        String topicId = topicIdFromUrl(url);
        if (!topicId.isEmpty()) {
            addIfMissing(urls, BASE_URL + "/t/" + topicId + ".json");
        }
        return urls;
    }

    static List<String> rssUrlsForTopic(String url) {
        List<String> urls = new ArrayList<>();
        String primary = rssUrlForTopic(url);
        if (!primary.isEmpty()) {
            urls.add(primary);
        }

        String topicId = topicIdFromUrl(url);
        if (!topicId.isEmpty()) {
            addIfMissing(urls, BASE_URL + "/t/" + topicId + ".rss");
        }
        return urls;
    }

    static String jsonUrlForTopic(String url) {
        String value = absoluteLinuxDoUrl(url);
        if (value.isEmpty()) {
            return "";
        }

        int fragment = value.indexOf('#');
        if (fragment >= 0) {
            value = value.substring(0, fragment);
        }
        int query = value.indexOf('?');
        if (query >= 0) {
            value = value.substring(0, query);
        }
        while (value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        if (value.endsWith(".json")) {
            return value;
        }
        if (topicIdFromUrl(value).isEmpty()) {
            return "";
        }
        return value + ".json";
    }

    static String rssUrlForTopic(String url) {
        String jsonUrl = jsonUrlForTopic(url);
        if (jsonUrl.endsWith(".json")) {
            return jsonUrl.substring(0, jsonUrl.length() - ".json".length()) + ".rss";
        }
        return "";
    }

    static String topicJsonRequestUrl(String jsonUrl) {
        if (jsonUrl == null || jsonUrl.trim().isEmpty()) {
            return "";
        }
        String separator = jsonUrl.contains("?") ? "&" : "?";
        return jsonUrl + separator + "track_visit=false&forceLoad=true";
    }

    static String postsJsonUrlForTopic(String topicId, List<String> postIds) {
        if (topicId == null || topicId.trim().isEmpty() || postIds == null || postIds.isEmpty()) {
            return "";
        }
        StringBuilder builder = new StringBuilder(BASE_URL)
                .append("/t/")
                .append(topicId.trim())
                .append("/posts.json?include_suggested=false");
        for (String postId : postIds) {
            if (postId != null && !postId.trim().isEmpty()) {
                builder.append("&post_ids%5B%5D=").append(postId.trim());
            }
        }
        return builder.toString();
    }

    private static String topicTitle(JSONObject root) {
        return root == null ? "" : root.optString("title", "").trim();
    }

    private static JSONArray streamIds(JSONObject root) {
        JSONObject stream = root == null ? null : root.optJSONObject("post_stream");
        JSONArray ids = stream == null ? null : stream.optJSONArray("stream");
        return ids == null ? new JSONArray() : ids;
    }

    private static List<JsonPost> sortedJsonPosts(JSONArray array) {
        List<JsonPost> posts = new ArrayList<>();
        for (int i = 0; i < array.length(); i++) {
            JSONObject item = array.optJSONObject(i);
            if (item != null) {
                posts.add(new JsonPost(item, i));
            }
        }
        Collections.sort(posts, (left, right) -> {
            int leftNumber = left.item.optInt("post_number", 0);
            int rightNumber = right.item.optInt("post_number", 0);
            if (leftNumber > 0 && rightNumber > 0 && leftNumber != rightNumber) {
                return Integer.compare(leftNumber, rightNumber);
            }
            return Integer.compare(left.index, right.index);
        });
        return posts;
    }

    private static Post postFromJson(JSONObject item, int fallbackNumber) {
        ParsedContent content = parsedCooked(item.optString("cooked", ""));
        if (content.text.isEmpty() && content.imageUrls.isEmpty()) {
            return null;
        }
        String replyContext = content.replyContext;
        int replyTo = item.optInt("reply_to_post_number", 0);
        if (replyContext.isEmpty() && replyTo > 0) {
            replyContext = "回复 #" + replyTo;
        }
        int postNumber = item.optInt("post_number", fallbackNumber);
        return new Post(
                firstNonEmpty(
                        item.optString("display_username", ""),
                        item.optString("username", ""),
                        item.optString("name", ""),
                        "楼层 " + Math.max(1, postNumber)
                ),
                discourseAvatar(item.optString("avatar_template", "")),
                postMeta(item),
                replyContext,
                content.text,
                content.imageUrls,
                content.inlineImages
        );
    }

    private static List<Element> sortedRssItems(Document document) {
        List<Element> items = new ArrayList<>(document.select("item"));
        boolean allDated = !items.isEmpty();
        for (Element item : items) {
            if (parseRssDateMillis(firstText(item, "pubDate")) <= 0L) {
                allDated = false;
                break;
            }
        }
        if (allDated) {
            Collections.sort(items, Comparator.comparingLong(
                    item -> parseRssDateMillis(firstText(item, "pubDate"))
            ));
        }
        return items;
    }

    static String postMeta(JSONObject item) {
        long created = parseInstantMillis(firstNonEmpty(
                item.optString("created_at", ""),
                item.optString("createdAt", "")
        ));
        long updated = parseInstantMillis(firstNonEmpty(
                item.optString("updated_at", ""),
                item.optString("last_version_at", ""),
                item.optString("updatedAt", "")
        ));

        String meta = "";
        if (created > 0L) {
            meta = "发表于 " + POST_TIME_FORMATTER.format(Instant.ofEpochMilli(created));
        }
        if (updated > 0L && updated > created + 60_000L) {
            String editText = "编辑 " + POST_TIME_FORMATTER.format(Instant.ofEpochMilli(updated));
            meta = meta.isEmpty() ? editText : meta + " · " + editText;
        }
        return meta;
    }

    private static String postMetaFromHtml(Element container) {
        Element time = container.selectFirst("time[datetime]");
        if (time == null) {
            return "";
        }
        long millis = parseInstantMillis(time.attr("datetime"));
        if (millis <= 0L) {
            String text = clean(time.text());
            return text.isEmpty() ? "" : "发表于 " + text;
        }
        return "发表于 " + POST_TIME_FORMATTER.format(Instant.ofEpochMilli(millis));
    }

    private static String postMetaFromRssDate(String value) {
        long millis = parseRssDateMillis(value);
        if (millis <= 0L) {
            return "";
        }
        return "发表于 " + POST_TIME_FORMATTER.format(Instant.ofEpochMilli(millis));
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

    private static String rssUrlForBoard(BoardDefinition board) {
        if ("latest".equals(board.boardId)) {
            return BASE_URL + "/latest.rss";
        }
        Matcher matcher = CATEGORY_ID.matcher(board.boardId);
        if (matcher.matches()) {
            return BASE_URL + "/c/" + matcher.group(1) + "/" + matcher.group(2) + ".rss";
        }
        String url = board.url.endsWith("/") ? board.url.substring(0, board.url.length() - 1) : board.url;
        return url + ".rss";
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

    private static void addIfMissing(List<String> values, String value) {
        if (value == null || value.trim().isEmpty()) {
            return;
        }
        if (!values.contains(value)) {
            values.add(value);
        }
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
        String replyContext = extractReplyContext(document);
        document.select("aside.quote, blockquote").remove();
        List<String> imageUrls = new ArrayList<>();
        List<Post.InlineImage> inlineImages = new ArrayList<>();
        for (Element image : document.select("img[src]")) {
            if (isInlineEmoji(image)) {
                String label = inlineEmojiLabel(image);
                String src = absoluteLinuxDoUrl(image.attr("src"));
                if (!src.isEmpty()) {
                    inlineImages.add(new Post.InlineImage(src, label));
                }
                image.replaceWith(new TextNode(" " + label + " "));
                continue;
            }
            String src = absoluteLinuxDoUrl(image.attr("src"));
            if (!src.isEmpty() && !imageUrls.contains(src)) {
                imageUrls.add(src);
            }
            image.remove();
        }
        String text = HtmlText.textWithLineBreaks(document.body());
        return new ParsedContent(text, replyContext, imageUrls, inlineImages);
    }

    private static ParsedContent parsedRssDescription(String html, String topicUrl) {
        Document document = Jsoup.parse(html == null ? "" : html, BASE_URL + "/");
        String topicId = topicIdFromUrl(topicUrl);
        for (Element anchor : document.select("a[href]")) {
            String text = clean(anchor.text()).toLowerCase();
            String hrefTopicId = topicIdFromUrl(anchor.attr("href"));
            boolean readFullTopicLink = text.contains("阅读完整话题")
                    || text.contains("read full topic")
                    || text.contains("read full discussion");
            if (readFullTopicLink || (!topicId.isEmpty() && topicId.equals(hrefTopicId))) {
                anchor.remove();
            }
        }
        return parsedCooked(document.body().html());
    }

    private static void addRssPostIfPresent(
            List<Post> posts,
            List<String> seenContent,
            Post post
    ) {
        String contentKey = clean(post.content);
        if (contentKey.isEmpty() && post.imageUrls.isEmpty()) {
            return;
        }
        if (!contentKey.isEmpty() && seenContent.contains(contentKey)) {
            return;
        }
        if (!contentKey.isEmpty()) {
            seenContent.add(contentKey);
        }
        posts.add(post);
    }

    private static String extractReplyContext(Document document) {
        Element quote = document.selectFirst("aside.quote blockquote, aside.quote, blockquote");
        if (quote == null) {
            return "";
        }
        String text = clean(quote.text());
        if (text.isEmpty()) {
            return "";
        }
        return "引用：" + abbreviate(text, 120);
    }

    private static String abbreviate(String text, int maxLength) {
        String value = clean(text);
        if (value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength).trim() + "...";
    }

    private static boolean isInlineEmoji(Element image) {
        String value = (
                image.attr("src")
                        + " " + image.attr("class")
                        + " " + image.attr("alt")
                        + " " + image.attr("title")
        ).toLowerCase();
        return value.contains("emoji")
                || value.contains("emoticon")
                || value.contains("twemoji")
                || value.contains("smiley");
    }

    private static String inlineEmojiLabel(Element image) {
        String label = firstNonEmpty(
                image.attr("alt"),
                image.attr("title"),
                image.attr("aria-label")
        );
        return label.isEmpty() ? "[表情]" : label;
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

    private static long parseRssDateMillis(String value) {
        if (value == null || value.trim().isEmpty()) {
            return 0L;
        }
        try {
            return ZonedDateTime.parse(value.trim(), DateTimeFormatter.RFC_1123_DATE_TIME)
                    .toInstant()
                    .toEpochMilli();
        } catch (Exception ignored) {
            return 0L;
        }
    }

    private static int repliesFromRssDescription(String description) {
        Matcher matcher = RSS_POST_COUNT.matcher(description == null ? "" : description);
        if (!matcher.find()) {
            return -1;
        }
        try {
            return Math.max(0, Integer.parseInt(matcher.group(1)) - 1);
        } catch (NumberFormatException ignored) {
            return -1;
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
        final String replyContext;
        final List<String> imageUrls;
        final List<Post.InlineImage> inlineImages;

        ParsedContent(
                String text,
                String replyContext,
                List<String> imageUrls,
                List<Post.InlineImage> inlineImages
        ) {
            this.text = text;
            this.replyContext = replyContext;
            this.imageUrls = imageUrls;
            this.inlineImages = inlineImages;
        }
    }

    private static final class JsonPost {
        final JSONObject item;
        final int index;

        JsonPost(JSONObject item, int index) {
            this.item = item;
            this.index = index;
        }
    }
}
