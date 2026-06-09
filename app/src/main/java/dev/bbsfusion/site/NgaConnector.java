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
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class NgaConnector implements ForumConnector {
    private static final String HOME_URL = "https://bbs.nga.cn/thread.php?fid=-7";
    private static final String LOGIN_URL = "https://bbs.nga.cn/nuke.php?__lib=login&__act=account&login";
    private static final String SUBJECT_API =
            "https://ngabbs.com/app_api.php?__lib=subject&__act=list";
    private static final String POST_API =
            "https://ngabbs.com/app_api.php?__lib=post&__act=list";
    private static final String CATEGORY_API =
            "https://bbs.nga.cn/app_api.php?__lib=home&__act=category";
    private static final String STID_PREFIX = "stid:";
    private static final Pattern BBCODE_IMAGE = Pattern.compile(
            "\\[img(?:=[^\\]]*)?\\](.*?)\\[/img\\]",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL
    );
    private static final Pattern HTML_IMAGE_SRC = Pattern.compile(
            "<img[^>]+(?:src|file|zoomfile)=[\"']?([^\"'\\s>]+)",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern QUOTE_BLOCK = Pattern.compile(
            "\\[quote[^\\]]*\\](.*?)\\[/quote\\]",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL
    );
    private static final Pattern PID_REPLY = Pattern.compile(
            "\\[pid=(\\d+)[^\\]]*\\](.*?)\\[/pid\\]",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL
    );
    private static final Pattern PLAIN_IMAGE_URL = Pattern.compile(
            "(?i)(?:(?:https?:)?//|attachments/|ngabbs/|\\./mon_|mon_)[^\\s\\]\\[\"'<>]+?\\.(?:jpg|jpeg|png|gif|webp)(?:\\?[^\\s\\]\\[\"'<>]*)?"
    );
    private static final DateTimeFormatter TIME_FORMATTER =
            DateTimeFormatter.ofPattern("M-d HH:mm").withZone(ZoneId.of("Asia/Shanghai"));
    private static final DateTimeFormatter POST_TIME_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-M-d HH:mm").withZone(ZoneId.of("Asia/Shanghai"));

    @Override
    public String id() {
        return "nga";
    }

    @Override
    public String name() {
        return "NGA";
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
        try {
            return fetchTopicsFromApi(board);
        } catch (IOException error) {
            Document document = NetworkClient.get(board.url, board.referrer);
            return ForumHtmlParsers.extractTopics(document, id(), board.url, board.sourceLabel);
        }
    }

    @Override
    public List<BoardDefinition> fetchAvailableBoards() throws IOException {
        List<BoardDefinition> boards = new ArrayList<>();
        for (BoardDefinition board : BoardCatalog.builtInBoards()) {
            if (!id().equals(board.siteId)) {
                continue;
            }
            boards.add(board);
        }

        try {
            boards = BoardCatalog.merge(boards, fetchCategoryBoards());
        } catch (IOException ignored) {
            // Keep static boards when the app category endpoint is temporarily unavailable.
        }

        String[] seeds = {"-7", "414", "300", "428", "489", "650", "706", "334", "436", "616"};
        for (String seed : seeds) {
            try {
                boards = BoardCatalog.merge(boards, fetchSubForums(seed));
            } catch (IOException ignored) {
                // Some NGA boards require login; keep the static catalog and continue.
            }
        }
        return boards;
    }

    @Override
    public TopicDetail fetchTopic(String url) throws IOException {
        try {
            TopicDetail detail = fetchTopicFromApi(url);
            if (!detail.posts.isEmpty()) {
                return detail;
            }
        } catch (IOException ignored) {
            // Fall back to the web page parser; some posts or sessions may not work with the app API.
        }
        Document document = NetworkClient.get(url, HOME_URL);
        return ForumHtmlParsers.extractTopic(document, url);
    }

    private List<TopicSummary> fetchTopicsFromApi(BoardDefinition board) throws IOException {
        JSONObject json = NetworkClient.postNgaApi(SUBJECT_API, boardForm(board));
        JSONObject result = json.optJSONObject("result");
        JSONArray data = result == null ? null : result.optJSONArray("data");
        List<TopicSummary> topics = new ArrayList<>();
        if (data == null) {
            return topics;
        }

        String forumName = json.optString("forumname", board.title);
        String label = board.sourceLabel;
        if (!forumName.isEmpty() && !label.contains(forumName)) {
            label = "NGA " + forumName;
        }

        for (int i = 0; i < data.length() && topics.size() < 80; i++) {
            JSONObject item = data.optJSONObject(i);
            if (item == null || !item.has("tid")) {
                continue;
            }

            long tid = item.optLong("tid", 0L);
            String title = item.optString("subject", "").trim();
            if (tid == 0L || title.length() < 2) {
                continue;
            }

            long lastPostSeconds = item.optLong("lastpost", item.optLong("postdate", 0L));
            long sortTimeMillis = lastPostSeconds > 0L ? lastPostSeconds * 1000L : 0L;
            String meta = label;
            if (sortTimeMillis > 0L) {
                meta += " · " + TIME_FORMATTER.format(Instant.ofEpochMilli(sortTimeMillis));
            }
            int replies = item.optInt("replies", -1);
            if (replies >= 0) {
                meta += " · " + replies + " 回复";
            }

            topics.add(new TopicSummary(
                    id(),
                    title,
                    "https://bbs.nga.cn/read.php?tid=" + tid,
                    meta,
                    sortTimeMillis
            ));
        }
        return topics;
    }

    private TopicDetail fetchTopicFromApi(String url) throws IOException {
        String tid = tidFromUrl(url);
        if (tid.isEmpty()) {
            throw new IOException("NGA 帖子链接缺少 tid。");
        }

        Map<String, String> form = new HashMap<>();
        form.put("tid", tid);
        JSONObject json = NetworkClient.postNgaApi(POST_API, form);
        JSONArray result = json.optJSONArray("result");
        if (result == null) {
            return new TopicDetail("帖子详情", url, new ArrayList<>());
        }

        List<Post> posts = new ArrayList<>();
        for (int i = 0; i < result.length() && posts.size() < 40; i++) {
            JSONObject item = result.optJSONObject(i);
            if (item == null) {
                continue;
            }

            String rawContent = item.optString("content", "");
            ParsedApiContent parsedContent = parseApiContent(item, rawContent);
            if (parsedContent.text.length() < 2
                    && parsedContent.imageUrls.isEmpty()
                    && parsedContent.inlineImages.isEmpty()) {
                continue;
            }
            String author = authorFromPostJson(item, json, posts.size());
            String avatarUrl = avatarFromPostJson(item, json);
            String meta = postMetaFromApiPost(item);
            posts.add(new Post(
                    author,
                    avatarUrl,
                    meta,
                    parsedContent.replyContext,
                    parsedContent.text,
                    parsedContent.imageUrls,
                    parsedContent.inlineImages
            ));
        }

        String title = json.optString("subject", "").trim();
        if (title.isEmpty() && result.length() > 0) {
            JSONObject first = result.optJSONObject(0);
            if (first != null) {
                title = first.optString("subject", "").trim();
            }
        }
        if (title.isEmpty()) {
            title = "帖子详情";
        }
        return new TopicDetail(title, url, posts);
    }

    private List<BoardDefinition> fetchCategoryBoards() throws IOException {
        JSONObject json = NetworkClient.postNgaApi(CATEGORY_API, new HashMap<>());
        return parseCategoryBoards(json);
    }

    static List<BoardDefinition> parseCategoryBoards(JSONObject json) {
        List<BoardDefinition> boards = new ArrayList<>();
        JSONArray result = json.optJSONArray("result");
        if (result != null) {
            collectCategoryBoards(result, boards);
        }
        return boards;
    }

    private static void collectCategoryBoards(JSONArray array, List<BoardDefinition> boards) {
        for (int i = 0; i < array.length(); i++) {
            JSONObject object = array.optJSONObject(i);
            if (object != null) {
                collectCategoryBoards(object, boards);
            }
        }
    }

    private static void collectCategoryBoards(JSONObject object, List<BoardDefinition> boards) {
        addCategoryBoard(object, boards);
        JSONArray groups = object.optJSONArray("groups");
        if (groups != null) {
            collectCategoryBoards(groups, boards);
        }
        JSONArray forums = object.optJSONArray("forums");
        if (forums != null) {
            collectCategoryBoards(forums, boards);
        }
        JSONArray children = object.optJSONArray("children");
        if (children != null) {
            collectCategoryBoards(children, boards);
        }
    }

    private static void addCategoryBoard(JSONObject object, List<BoardDefinition> boards) {
        String name = object.optString("name", "").trim();
        if (name.isEmpty()) {
            return;
        }

        String stid = object.optString("stid", "").trim();
        if (usableId(stid)) {
            boards.add(new BoardDefinition(
                    "nga",
                    STID_PREFIX + stid,
                    name,
                    "https://bbs.nga.cn/thread.php?stid=" + stid,
                    "https://bbs.nga.cn/",
                    "NGA " + name
            ));
            return;
        }

        String fid = object.optString("fid", "").trim();
        if (!usableId(fid)) {
            return;
        }

        boards.add(new BoardDefinition(
                "nga",
                fid,
                name,
                "https://bbs.nga.cn/thread.php?fid=" + fid,
                "https://bbs.nga.cn/",
                "NGA " + name
        ));
    }

    private static boolean usableId(String id) {
        return id != null
                && !id.isEmpty()
                && !"0".equals(id)
                && !"null".equalsIgnoreCase(id);
    }

    private List<BoardDefinition> fetchSubForums(String fid) throws IOException {
        Map<String, String> form = new HashMap<>();
        form.put("fid", fid);
        JSONObject json = NetworkClient.postNgaApi(SUBJECT_API, form);
        JSONObject result = json.optJSONObject("result");
        JSONArray subForums = result == null ? null : result.optJSONArray("subForum");
        List<BoardDefinition> boards = new ArrayList<>();
        if (subForums == null) {
            return boards;
        }

        for (int i = 0; i < subForums.length(); i++) {
            JSONObject item = subForums.optJSONObject(i);
            if (item == null) {
                continue;
            }
            long subId = item.optLong("id", 0L);
            String name = item.optString("name", "").trim();
            if (subId == 0L || name.isEmpty()) {
                continue;
            }
            boards.add(new BoardDefinition(
                    id(),
                    STID_PREFIX + subId,
                    name,
                    "https://bbs.nga.cn/thread.php?stid=" + subId,
                    "https://bbs.nga.cn/",
                    "NGA " + name
            ));
        }
        return boards;
    }

    private Map<String, String> boardForm(BoardDefinition board) {
        Map<String, String> form = new HashMap<>();
        if (board.boardId.startsWith(STID_PREFIX)) {
            form.put("stid", board.boardId.substring(STID_PREFIX.length()));
        } else {
            form.put("fid", board.boardId);
        }
        return form;
    }

    private static String tidFromUrl(String url) {
        int index = url.indexOf("tid=");
        if (index < 0) {
            return "";
        }
        int start = index + 4;
        int end = start;
        while (end < url.length() && Character.isDigit(url.charAt(end))) {
            end++;
        }
        return end > start ? url.substring(start, end) : "";
    }

    static String authorFromPostJson(JSONObject item, JSONObject root, int index) {
        String author = authorFromUserObject(item.optJSONObject("author"));
        if (!author.isEmpty()) {
            return author;
        }

        author = firstNonEmpty(
                stringValue(item, "author"),
                stringValue(item, "username"),
                stringValue(item, "name")
        );
        if (!author.isEmpty()) {
            return author;
        }

        String authorId = firstNonEmpty(
                stringValue(item, "authorid"),
                stringValue(item, "uid"),
                stringValue(item, "posterid")
        );
        if (!authorId.isEmpty()) {
            author = authorFromUserMap(root.optJSONObject("__U"), authorId);
            if (!author.isEmpty()) {
                return author;
            }
            author = authorFromUserMap(root.optJSONObject("users"), authorId);
            if (!author.isEmpty()) {
                return author;
            }
        }

        return "楼层 " + (index + 1);
    }

    static String avatarFromPostJson(JSONObject item, JSONObject root) {
        String avatar = avatarFromJsonObject(item);
        if (!avatar.isEmpty()) {
            return avatar;
        }
        avatar = avatarFromJsonObject(item.optJSONObject("author"));
        if (!avatar.isEmpty()) {
            return avatar;
        }

        String authorId = firstNonEmpty(
                stringValue(item, "authorid"),
                stringValue(item, "uid"),
                stringValue(item, "posterid")
        );
        if (authorId.isEmpty()) {
            return "";
        }

        avatar = avatarFromUserMap(root.optJSONObject("__U"), authorId);
        if (!avatar.isEmpty()) {
            return avatar;
        }
        return avatarFromUserMap(root.optJSONObject("users"), authorId);
    }

    private static String authorFromUserMap(JSONObject users, String authorId) {
        if (users == null) {
            return "";
        }
        JSONObject user = users.optJSONObject(authorId);
        if (user == null) {
            return "";
        }
        return authorFromUserObject(user);
    }

    private static String authorFromUserObject(JSONObject user) {
        if (user == null) {
            return "";
        }
        return firstNonEmpty(
                stringValue(user, "username"),
                stringValue(user, "name"),
                stringValue(user, "author")
        );
    }

    private static String avatarFromUserMap(JSONObject users, String authorId) {
        if (users == null) {
            return "";
        }
        JSONObject user = users.optJSONObject(authorId);
        if (user == null) {
            return "";
        }
        return avatarFromJsonObject(user);
    }

    private static String avatarFromJsonObject(JSONObject object) {
        if (object == null) {
            return "";
        }
        return normalizeNgaAvatarUrl(firstNonEmpty(
                stringValue(object, "avatar"),
                stringValue(object, "avatarurl"),
                stringValue(object, "avatarUrl"),
                stringValue(object, "avatar_url"),
                stringValue(object, "icon"),
                stringValue(object, "usericon"),
                stringValue(object, "userIcon")
        ));
    }

    private static String normalizeNgaAvatarUrl(String avatar) {
        if (avatar == null) {
            return "";
        }
        String value = avatar.trim();
        if (value.isEmpty()
                || "0".equals(value)
                || "null".equalsIgnoreCase(value)
                || "false".equalsIgnoreCase(value)) {
            return "";
        }
        if (value.startsWith("https://") || value.startsWith("http://")) {
            return value;
        }
        if (value.startsWith("//")) {
            return value.contains("img.nga.178.com/attachments/")
                    ? "http:" + value
                    : "https:" + value;
        }
        if (value.startsWith("/")) {
            return "http://img.nga.178.com" + value;
        }
        if (value.startsWith("attachments/") || value.startsWith("ngabbs/")) {
            return "http://img.nga.178.com/" + value;
        }
        return value;
    }

    private static String stringValue(JSONObject object, String key) {
        if (object == null) {
            return "";
        }
        Object value = object.opt(key);
        if (value == null
                || value == JSONObject.NULL
                || value instanceof JSONObject
                || value instanceof JSONArray) {
            return "";
        }
        return String.valueOf(value).trim();
    }

    static List<String> imageUrlsFromApiPost(JSONObject item, String content) {
        List<String> urls = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        collectImageUrlsFromText(content, urls, seen);
        collectAttachmentImages(item.opt("attachments"), urls, seen);
        collectAttachmentImages(item.opt("attachs"), urls, seen);
        collectAttachmentImages(item.opt("attach"), urls, seen);
        return urls;
    }

    static ParsedApiContent parseApiContent(JSONObject item, String rawContent) {
        String replyContext = extractReplyContext(rawContent);
        String content = removeReplyBlocks(rawContent);
        List<Post.InlineImage> inlineImages = inlineImagesFromHtml(content);
        List<String> imageUrls = imageUrlsFromApiPost(item, removeInlineHtmlImages(content));
        String text = cleanApiContent(content);
        return new ParsedApiContent(text, replyContext, imageUrls, inlineImages);
    }

    static String postMetaFromApiPost(JSONObject item) {
        String posted = timeTextFromFields(
                item,
                "发表于",
                "postdate",
                "postDate",
                "post_date",
                "post_time",
                "posttime",
                "created",
                "created_at",
                "timestamp",
                "time",
                "dateline"
        );
        String edited = timeTextFromFields(
                item,
                "编辑",
                "lastmodify",
                "lastModify",
                "last_modified",
                "modifytime",
                "edittime",
                "lastmodifytime",
                "last_update",
                "updated_at",
                "alterdate",
                "alter_time"
        );

        String meta = "";
        if (!posted.isEmpty()) {
            meta = posted;
        }
        if (!edited.isEmpty() && !edited.equals(posted)) {
            meta = meta.isEmpty() ? edited : meta + " · " + edited;
        }

        String alterInfo = cleanApiContent(firstNonEmpty(
                stringValue(item, "alterinfo"),
                stringValue(item, "alter_info"),
                stringValue(item, "lastmodifyinfo")
        ));
        if (!alterInfo.isEmpty() && !meta.contains("编辑")) {
            meta = meta.isEmpty() ? alterInfo : meta + " · " + alterInfo;
        }
        return meta;
    }

    private static String extractReplyContext(String rawContent) {
        String content = rawContent == null ? "" : rawContent;
        Matcher quote = QUOTE_BLOCK.matcher(content);
        if (quote.find()) {
            String text = cleanApiContent(quote.group(1));
            if (!text.isEmpty()) {
                return "引用：" + abbreviate(text, 120);
            }
        }

        Matcher pid = PID_REPLY.matcher(content);
        if (pid.find()) {
            String label = "回复 #" + pid.group(1);
            String text = cleanApiContent(pid.group(2));
            if (!text.isEmpty()) {
                return label + "：" + abbreviate(text, 80);
            }
            return label;
        }
        return "";
    }

    private static String removeReplyBlocks(String rawContent) {
        String content = rawContent == null ? "" : rawContent;
        content = QUOTE_BLOCK.matcher(content).replaceAll(" ");
        return PID_REPLY.matcher(content).replaceAll(" ");
    }

    private static List<Post.InlineImage> inlineImagesFromHtml(String html) {
        List<Post.InlineImage> inlineImages = new ArrayList<>();
        if (html == null || !html.toLowerCase().contains("<img")) {
            return inlineImages;
        }
        Document document = Jsoup.parseBodyFragment(html);
        for (Element image : document.select("img")) {
            String label = inlineImageLabel(image);
            if (label.isEmpty()) {
                continue;
            }
            String source = normalizeNgaInlineImageUrl(firstNonEmpty(
                    image.attr("zoomfile"),
                    image.attr("file"),
                    image.attr("data-original"),
                    image.attr("data-src"),
                    image.attr("src")
            ));
            if (!source.isEmpty()) {
                inlineImages.add(new Post.InlineImage(source, label));
            }
        }
        return inlineImages;
    }

    private static String removeInlineHtmlImages(String html) {
        if (html == null || !html.toLowerCase().contains("<img")) {
            return html == null ? "" : html;
        }
        Document document = Jsoup.parseBodyFragment(html);
        for (Element image : document.select("img")) {
            if (inlineImageLabel(image).isEmpty()) {
                continue;
            }
            image.remove();
        }
        return document.body().html();
    }

    private static String normalizeNgaInlineImageUrl(String rawUrl) {
        if (rawUrl == null) {
            return "";
        }
        String value = rawUrl.trim();
        if (value.isEmpty() || value.startsWith("data:")) {
            return "";
        }
        int bracket = value.indexOf('[');
        if (bracket >= 0) {
            value = value.substring(0, bracket).trim();
        }
        if (value.startsWith("https://") || value.startsWith("http://")) {
            return value;
        }
        if (value.startsWith("//")) {
            return value.contains("img.nga.178.com/attachments/")
                    ? "http:" + value
                    : "https:" + value;
        }
        if (value.startsWith("./")) {
            value = value.substring(2);
        }
        if (value.startsWith("/smiley/") || value.startsWith("smiley/")) {
            return value.startsWith("/")
                    ? "https://bbs.nga.cn" + value
                    : "https://bbs.nga.cn/" + value;
        }
        if (value.startsWith("/")) {
            return "http://img.nga.178.com" + value;
        }
        if (value.startsWith("attachments/") || value.startsWith("ngabbs/")) {
            return "http://img.nga.178.com/" + value;
        }
        if (value.startsWith("mon_")) {
            return "http://img.nga.178.com/attachments/" + value;
        }
        return "";
    }

    private static String timeTextFromFields(JSONObject item, String label, String... keys) {
        for (String key : keys) {
            String raw = stringValue(item, key);
            if (raw.isEmpty()) {
                continue;
            }
            Long epochSeconds = epochSeconds(raw);
            if (epochSeconds != null && epochSeconds > 0L) {
                return label + " " + POST_TIME_FORMATTER.format(Instant.ofEpochSecond(epochSeconds));
            }
            String text = cleanApiContent(raw);
            if (text.isEmpty()
                    || "0".equals(text)
                    || "null".equalsIgnoreCase(text)
                    || "false".equalsIgnoreCase(text)) {
                continue;
            }
            return text.startsWith(label) ? text : label + " " + text;
        }
        return "";
    }

    private static Long epochSeconds(String raw) {
        String value = raw == null ? "" : raw.trim();
        if (!value.matches("\\d{9,13}")) {
            return null;
        }
        try {
            long numeric = Long.parseLong(value);
            if (numeric > 100_000_000_000L) {
                numeric /= 1000L;
            }
            return numeric;
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static String abbreviate(String text, int maxLength) {
        String value = cleanApiContent(text);
        if (value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength).trim() + "...";
    }

    private static void collectImageUrlsFromText(String text, List<String> urls, Set<String> seen) {
        if (text == null || text.isEmpty()) {
            return;
        }

        Matcher bbcode = BBCODE_IMAGE.matcher(text);
        while (bbcode.find()) {
            addImageUrl(bbcode.group(1), urls, seen);
        }

        Matcher html = HTML_IMAGE_SRC.matcher(text);
        while (html.find()) {
            addImageUrl(html.group(1), urls, seen);
        }

        Matcher plain = PLAIN_IMAGE_URL.matcher(text);
        while (plain.find()) {
            addImageUrl(plain.group(), urls, seen);
        }
    }

    private static void collectAttachmentImages(Object value, List<String> urls, Set<String> seen) {
        if (value == null || value == JSONObject.NULL || urls.size() >= 12) {
            return;
        }
        if (value instanceof JSONArray) {
            JSONArray array = (JSONArray) value;
            for (int i = 0; i < array.length(); i++) {
                collectAttachmentImages(array.opt(i), urls, seen);
            }
            return;
        }
        if (!(value instanceof JSONObject)) {
            addImageUrl(String.valueOf(value), urls, seen);
            return;
        }

        JSONObject object = (JSONObject) value;
        addImageUrl(firstNonEmpty(
                stringValue(object, "url"),
                stringValue(object, "path"),
                stringValue(object, "attachurl"),
                stringValue(object, "attachUrl"),
                stringValue(object, "src"),
                stringValue(object, "file")
        ), urls, seen);

        JSONArray names = object.names();
        if (names == null) {
            return;
        }
        for (int i = 0; i < names.length() && urls.size() < 12; i++) {
            String name = names.optString(i, "");
            Object child = object.opt(name);
            if (child instanceof JSONObject || child instanceof JSONArray) {
                collectAttachmentImages(child, urls, seen);
            }
        }
    }

    private static void addImageUrl(String rawUrl, List<String> urls, Set<String> seen) {
        if (urls.size() >= 12) {
            return;
        }
        String url = normalizeNgaImageUrl(rawUrl);
        if (url.isEmpty() || !seen.add(url)) {
            return;
        }
        urls.add(url);
    }

    private static String normalizeNgaImageUrl(String rawUrl) {
        if (rawUrl == null) {
            return "";
        }
        String value = rawUrl.trim();
        if (value.isEmpty()
                || value.startsWith("data:")
                || value.contains("/smiley/")
                || value.contains("emoticon")) {
            return "";
        }
        int bracket = value.indexOf('[');
        if (bracket >= 0) {
            value = value.substring(0, bracket).trim();
        }
        if (value.startsWith("https://") || value.startsWith("http://")) {
            return value;
        }
        if (value.startsWith("//")) {
            return value.contains("img.nga.178.com/attachments/")
                    ? "http:" + value
                    : "https:" + value;
        }
        if (value.startsWith("./")) {
            value = value.substring(2);
        }
        if (value.startsWith("/")) {
            return "http://img.nga.178.com" + value;
        }
        if (value.startsWith("attachments/")) {
            return "http://img.nga.178.com/" + value;
        }
        if (value.startsWith("ngabbs/")) {
            return "https://img.nga.178.com/" + value;
        }
        if (value.startsWith("mon_")) {
            return "http://img.nga.178.com/attachments/" + value;
        }
        return "";
    }

    static String cleanApiContent(String content) {
        String value = content == null ? "" : content;
        value = QUOTE_BLOCK.matcher(value).replaceAll(" ");
        value = PID_REPLY.matcher(value).replaceAll(" ");
        value = BBCODE_IMAGE.matcher(value).replaceAll(" ");
        value = replaceHtmlImageLabels(value);
        value = value.replace("[br]", "\n")
                .replace("[BR]", "\n")
                .replace("<br>", "\n")
                .replace("<br/>", "\n")
                .replace("<br />", "\n");
        return value.replace('\u00a0', ' ')
                .replaceAll("<[^>]+>", " ")
                .replaceAll("\\r?\\n\\s*", "\n")
                .replaceAll("[ \\t]+", " ")
                .trim();
    }

    private static String replaceHtmlImageLabels(String html) {
        if (html == null || html.indexOf("<img") < 0) {
            return html == null ? "" : html;
        }
        Document document = Jsoup.parseBodyFragment(html);
        for (Element image : document.select("img")) {
            String label = inlineImageLabel(image);
            if (label.isEmpty()) {
                image.remove();
            } else {
                image.replaceWith(new TextNode(" " + label + " "));
            }
        }
        return document.body().html();
    }

    private static String inlineImageLabel(Element image) {
        String value = (
                image.attr("src")
                        + " " + image.attr("file")
                        + " " + image.attr("class")
                        + " " + image.attr("alt")
                        + " " + image.attr("title")
        ).toLowerCase();
        boolean emoticon = value.contains("/smiley/")
                || value.contains("emoticon")
                || value.contains("emoji")
                || value.contains("ubb");
        if (!emoticon) {
            return "";
        }
        String label = firstNonEmpty(
                image.attr("alt"),
                image.attr("title"),
                image.attr("data-code"),
                image.attr("aria-label")
        );
        return label.isEmpty() ? "[表情]" : label;
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

    static final class ParsedApiContent {
        final String text;
        final String replyContext;
        final List<String> imageUrls;
        final List<Post.InlineImage> inlineImages;

        ParsedApiContent(
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
}
