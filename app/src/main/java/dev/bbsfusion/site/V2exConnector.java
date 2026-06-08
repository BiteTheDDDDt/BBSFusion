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
import java.net.URLEncoder;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class V2exConnector implements ForumConnector {
    private static final String HOME_URL = "https://www.v2ex.com/";
    private static final String LOGIN_URL = "https://www.v2ex.com/signin";
    private static final String LATEST_API = "https://www.v2ex.com/api/topics/latest.json";
    private static final String NODE_TOPICS_API = "https://www.v2ex.com/api/topics/show.json?node_name=";
    private static final String TOPIC_API = "https://www.v2ex.com/api/topics/show.json?id=";
    private static final String REPLIES_API = "https://www.v2ex.com/api/replies/show.json?topic_id=";
    private static final String NODES_API = "https://www.v2ex.com/api/nodes/all.json";
    private static final Pattern TOPIC_ID = Pattern.compile("(?:/t/|[?&]id=)(\\d+)");
    private static final DateTimeFormatter TIME_FORMATTER =
            DateTimeFormatter.ofPattern("M-d HH:mm").withZone(ZoneId.of("Asia/Shanghai"));

    @Override
    public String id() {
        return "v2ex";
    }

    @Override
    public String name() {
        return "V2EX";
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
        String endpoint = "latest".equals(board.boardId)
                ? LATEST_API
                : NODE_TOPICS_API + urlEncode(board.boardId);
        return parseTopics(NetworkClient.getJsonArray(endpoint, board.referrer), board);
    }

    @Override
    public List<BoardDefinition> fetchAvailableBoards() throws IOException {
        List<BoardDefinition> boards = new ArrayList<>();
        for (BoardDefinition board : BoardCatalog.builtInBoards()) {
            if (id().equals(board.siteId)) {
                boards.add(board);
            }
        }

        JSONArray nodes;
        try {
            nodes = NetworkClient.getJsonArray(NODES_API, HOME_URL);
        } catch (IOException ignored) {
            return boards;
        }
        List<BoardDefinition> fetched = new ArrayList<>();
        for (int i = 0; i < nodes.length(); i++) {
            JSONObject node = nodes.optJSONObject(i);
            if (node == null) {
                continue;
            }
            String name = node.optString("name", "").trim();
            String title = node.optString("title", "").trim();
            if (name.isEmpty() || title.isEmpty()) {
                continue;
            }
            fetched.add(new BoardDefinition(
                    id(),
                    name,
                    title,
                    "https://www.v2ex.com/go/" + name,
                    HOME_URL,
                    "V2EX " + title
            ));
        }
        return BoardCatalog.merge(boards, fetched);
    }

    @Override
    public TopicDetail fetchTopic(String url) throws IOException {
        String topicId = topicIdFromUrl(url);
        if (topicId.isEmpty()) {
            throw new IOException("V2EX 帖子链接缺少 topic id。");
        }

        JSONArray topicArray = NetworkClient.getJsonArray(TOPIC_API + topicId, HOME_URL);
        JSONObject topic = topicArray.length() == 0 ? null : topicArray.optJSONObject(0);
        JSONArray replies = NetworkClient.getJsonArray(REPLIES_API + topicId, HOME_URL);

        List<Post> posts = new ArrayList<>();
        if (topic != null) {
            ParsedContent content = parsedContent(
                    topic.optString("content_rendered", ""),
                    topic.optString("content", "")
            );
            if (!content.text.isEmpty() || !content.imageUrls.isEmpty()) {
                JSONObject member = topic.optJSONObject("member");
                posts.add(new Post(
                        memberName(member, "楼主"),
                        memberAvatar(member),
                        content.text,
                        content.imageUrls
                ));
            }
        }

        for (int i = 0; i < replies.length() && posts.size() < 80; i++) {
            JSONObject reply = replies.optJSONObject(i);
            if (reply == null) {
                continue;
            }
            ParsedContent content = parsedContent(
                    reply.optString("content_rendered", ""),
                    reply.optString("content", "")
            );
            if (content.text.isEmpty() && content.imageUrls.isEmpty()) {
                continue;
            }
            JSONObject member = reply.optJSONObject("member");
            posts.add(new Post(
                    memberName(member, "楼层 " + (posts.size() + 1)),
                    memberAvatar(member),
                    content.text,
                    content.imageUrls
            ));
        }

        String title = topic == null ? "" : topic.optString("title", "").trim();
        return new TopicDetail(title.isEmpty() ? "帖子详情" : title, url, posts);
    }

    static List<TopicSummary> parseTopics(JSONArray array, BoardDefinition board) {
        List<TopicSummary> topics = new ArrayList<>();
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

            long timestamp = firstPositive(
                    item.optLong("last_touched", 0L),
                    item.optLong("last_modified", 0L),
                    item.optLong("created", 0L)
            );
            long sortTimeMillis = timestamp > 0L ? timestamp * 1000L : 0L;

            String label = board.sourceLabel;
            JSONObject node = item.optJSONObject("node");
            if ("latest".equals(board.boardId) && node != null) {
                String nodeTitle = node.optString("title", "").trim();
                if (!nodeTitle.isEmpty()) {
                    label = "V2EX " + nodeTitle;
                }
            }
            String meta = label;
            if (sortTimeMillis > 0L) {
                meta += " · " + TIME_FORMATTER.format(Instant.ofEpochMilli(sortTimeMillis));
            }
            int replies = item.optInt("replies", -1);
            if (replies >= 0) {
                meta += " · " + replies + " 回复";
            }

            String url = item.optString("url", "").trim();
            if (url.isEmpty()) {
                url = "https://www.v2ex.com/t/" + topicId;
            }
            topics.add(new TopicSummary("v2ex", title, normalizeUrl(url), meta, sortTimeMillis));
        }
        return topics;
    }

    static String topicIdFromUrl(String url) {
        if (url == null) {
            return "";
        }
        Matcher matcher = TOPIC_ID.matcher(url);
        return matcher.find() ? matcher.group(1) : "";
    }

    static ParsedContent parsedContent(String rendered, String fallback) {
        String html = rendered == null || rendered.trim().isEmpty() ? fallback : rendered;
        Document document = Jsoup.parse(html == null ? "" : html, HOME_URL);
        List<String> imageUrls = new ArrayList<>();
        for (Element image : document.select("img[src]")) {
            String src = normalizeUrl(image.attr("src"));
            if (!src.isEmpty() && !imageUrls.contains(src)) {
                imageUrls.add(src);
            }
            image.remove();
        }
        String text = document.text().replace('\u00a0', ' ').replaceAll("\\s+", " ").trim();
        if (text.isEmpty() && fallback != null) {
            text = fallback.replace('\u00a0', ' ').replaceAll("\\s+", " ").trim();
        }
        return new ParsedContent(text, imageUrls);
    }

    private static String memberName(JSONObject member, String fallback) {
        if (member == null) {
            return fallback;
        }
        String username = member.optString("username", "").trim();
        return username.isEmpty() ? fallback : username;
    }

    private static String memberAvatar(JSONObject member) {
        if (member == null) {
            return "";
        }
        return normalizeUrl(firstNonEmpty(
                member.optString("avatar_large", ""),
                member.optString("avatar_normal", ""),
                member.optString("avatar_mini", "")
        ));
    }

    private static String normalizeUrl(String url) {
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
            return "https://www.v2ex.com" + value;
        }
        return value;
    }

    private static long firstPositive(long... values) {
        for (long value : values) {
            if (value > 0L) {
                return value;
            }
        }
        return 0L;
    }

    private static String firstNonEmpty(String... values) {
        for (String value : values) {
            if (value != null && !value.trim().isEmpty()) {
                return value.trim();
            }
        }
        return "";
    }

    private static String urlEncode(String value) {
        try {
            return URLEncoder.encode(value, "UTF-8");
        } catch (Exception ignored) {
            return value;
        }
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
