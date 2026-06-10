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
import org.jsoup.parser.Parser;

import java.io.IOException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
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
    private static final String EMOTICON_BASE_URL = "https://img4.nga.178.com/ngabbs/post/smile/";
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
    private static final Pattern UBB_EMOTICON = Pattern.compile(
            "\\[s:([^:\\]]+):([^\\]]+)]",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern LEGACY_EMOTICON = Pattern.compile(
            "\\[s:(\\d+)]",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern BBCODE_URL = Pattern.compile(
            "\\[url(?:=[^\\]]*)?\\](.*?)\\[/url\\]",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL
    );
    private static final Pattern BBCODE_UID = Pattern.compile(
            "\\[uid=\\d+](.*?)\\[/uid]",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL
    );
    private static final Pattern BBCODE_SIMPLE_TAG = Pattern.compile(
            "\\[/?(?:b|i|u|del|color|size|align|font|h|collapse)[^\\]]*]",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern REPLY_TO_HEADER = Pattern.compile(
            "(?i)(?:^|[\\s>])Reply\\s+to\\s+(?:Post\\s+by\\s*)?.{1,160}?(?:\\([^)]*\\)\\s*[:：]|[:：])"
    );
    private static final Pattern LEADING_REPLY_QUOTE_BLOCK = Pattern.compile(
            "(?is)^\\s*>?\\s*Reply\\s+to\\s+(?:Post\\s+by\\s*)?.{1,220}?(?:\\([^)]*\\)\\s*[:：]|[:：]).*?(?:\\r?\\n\\s*\\r?\\n)+"
    );
    private static final Pattern POST_BY_HEADER = Pattern.compile(
            "(?i)(?:Reply to )?Post by .*?(?:\\([^)]*\\):|:)"
    );
    private static final Pattern API_BR = Pattern.compile("(?i)\\[(?:br|/br)]");
    private static final Pattern HTML_LINE_BREAK = Pattern.compile("(?i)<br\\s*/?>");
    private static final Pattern HTML_BLOCK_BOUNDARY = Pattern.compile(
            "(?i)</?(?:p|div|section|article|blockquote|ul|ol|li|table|thead|tbody|tfoot|tr|td|th|h[1-6])\\b[^>]*>"
    );
    private static final Pattern HTML_ADJACENT_BLOCK_BOUNDARY = Pattern.compile(
            "(?i)</(?:p|div|section|article|blockquote|ul|ol|li|table|thead|tbody|tfoot|tr|td|th|h[1-6])\\s*>\\s*"
                    + "<(?:p|div|section|article|blockquote|ul|ol|li|table|thead|tbody|tfoot|tr|td|th|h[1-6])\\b[^>]*>"
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
            return fetchTopicsFromApiPage(board, 1);
        } catch (IOException error) {
            Document document = NetworkClient.get(board.url, board.referrer);
            return ForumHtmlParsers.extractTopics(document, id(), board.url, board.sourceLabel);
        }
    }

    @Override
    public List<TopicSummary> fetchTopics(BoardDefinition board, int page) throws IOException {
        try {
            return fetchTopicsFromApiPage(board, page);
        } catch (IOException error) {
            String pageUrl = pagedBoardUrl(board.url, page);
            Document document = NetworkClient.get(pageUrl, board.referrer);
            return ForumHtmlParsers.extractTopics(document, id(), pageUrl, board.sourceLabel);
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
        return fetchTopicPage(url, 1);
    }

    @Override
    public TopicDetail fetchTopicPage(String url, int page) throws IOException {
        try {
            TopicDetail detail = fetchTopicFromApi(url, page);
            if (!detail.posts.isEmpty()) {
                return detail;
            }
        } catch (IOException ignored) {
            // Fall back to the web page parser; some posts or sessions may not work with the app API.
        }
        Document document = NetworkClient.get(url, HOME_URL);
        return ForumHtmlParsers.extractTopic(document, url, page);
    }

    private List<TopicSummary> fetchTopicsFromApiPage(BoardDefinition board, int page) throws IOException {
        JSONObject json = NetworkClient.postNgaApi(SUBJECT_API, boardForm(board, page));
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

    private TopicDetail fetchTopicFromApi(String url, int page) throws IOException {
        String tid = tidFromUrl(url);
        if (tid.isEmpty()) {
            throw new IOException("NGA 帖子链接缺少 tid。");
        }

        Map<String, String> form = new HashMap<>();
        form.put("tid", tid);
        form.put("page", String.valueOf(Math.max(1, page)));
        JSONObject json = NetworkClient.postNgaApi(POST_API, form);
        JSONArray result = json.optJSONArray("result");
        if (result == null) {
            return new TopicDetail("帖子详情", url, new ArrayList<>(), page, false);
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
        return new TopicDetail(title, url, posts, page, result.length() >= 20);
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

    static Map<String, String> boardForm(BoardDefinition board, int page) {
        Map<String, String> form = new HashMap<>();
        if (board.boardId.startsWith(STID_PREFIX)) {
            form.put("stid", board.boardId.substring(STID_PREFIX.length()));
        } else {
            form.put("fid", board.boardId);
        }
        if (page > 1) {
            form.put("page", String.valueOf(page));
        }
        return form;
    }

    static String pagedBoardUrl(String url, int page) {
        if (page <= 1 || url == null || url.isEmpty()) {
            return url;
        }
        if (url.contains("page=")) {
            return url.replaceFirst("([?&]page=)\\d+", "$1" + page);
        }
        return url + (url.contains("?") ? "&" : "?") + "page=" + page;
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
        ParsedInlineContent inlineContent = parseInlineContent(content);
        List<String> imageUrls = imageUrlsFromApiPost(item, inlineContent.content);
        String text = cleanApiContent(inlineContent.content);
        List<Post.InlineImage> inlineImages = inlineContent.inlineImages;
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
        content = QUOTE_BLOCK.matcher(content).replaceAll("\n");
        return PID_REPLY.matcher(content).replaceAll("\n");
    }

    private static ParsedInlineContent parseInlineContent(String content) {
        List<Post.InlineImage> inlineImages = new ArrayList<>();
        String value = content == null ? "" : content;
        value = replaceHtmlInlineImages(value, inlineImages);
        value = replaceBbcodeInlineImages(value, inlineImages);
        value = replacePlainInlineSmileUrls(value, inlineImages);
        value = markUbbEmoticons(value, inlineImages);
        return new ParsedInlineContent(value, inlineImages);
    }

    private static String replaceHtmlInlineImages(String html, List<Post.InlineImage> inlineImages) {
        if (html == null || !html.toLowerCase(Locale.ROOT).contains("<img")) {
            return html == null ? "" : html;
        }
        Document document = Jsoup.parseBodyFragment(html);
        for (Element image : document.select("img")) {
            String source = normalizeNgaInlineImageUrl(firstNonEmpty(
                    image.attr("zoomfile"),
                    image.attr("file"),
                    image.attr("data-original"),
                    image.attr("data-src"),
                    image.attr("src")
            ));
            if (source.isEmpty() || !isInlineEmoticonImageUrl(source)) {
                continue;
            }
            String label = inlineImageLabel(image);
            if (label.isEmpty() || "[表情]".equals(label)) {
                label = labelForNgaSmileUrl(source);
            }
            addInlineImage(inlineImages, source, label);
            image.replaceWith(new TextNode(" " + label + " "));
        }
        return document.body().html();
    }

    private static String replaceBbcodeInlineImages(String content, List<Post.InlineImage> inlineImages) {
        Matcher matcher = BBCODE_IMAGE.matcher(content);
        StringBuffer buffer = new StringBuffer();
        while (matcher.find()) {
            String source = normalizeNgaInlineImageUrl(matcher.group(1));
            if (source.isEmpty() || !isInlineEmoticonImageUrl(source)) {
                matcher.appendReplacement(buffer, Matcher.quoteReplacement(matcher.group()));
                continue;
            }
            String label = labelForNgaSmileUrl(source);
            addInlineImage(inlineImages, source, label);
            matcher.appendReplacement(buffer, Matcher.quoteReplacement(" " + label + " "));
        }
        matcher.appendTail(buffer);
        return buffer.toString();
    }

    private static String replacePlainInlineSmileUrls(String content, List<Post.InlineImage> inlineImages) {
        Matcher matcher = PLAIN_IMAGE_URL.matcher(content);
        StringBuffer buffer = new StringBuffer();
        while (matcher.find()) {
            String source = normalizeNgaInlineImageUrl(matcher.group());
            if (source.isEmpty() || !isInlineEmoticonImageUrl(source)) {
                matcher.appendReplacement(buffer, Matcher.quoteReplacement(matcher.group()));
                continue;
            }
            String label = labelForNgaSmileUrl(source);
            addInlineImage(inlineImages, source, label);
            matcher.appendReplacement(buffer, Matcher.quoteReplacement(" " + label + " "));
        }
        matcher.appendTail(buffer);
        return buffer.toString();
    }

    private static String markUbbEmoticons(String content, List<Post.InlineImage> inlineImages) {
        String value = replaceNamedUbbEmoticons(content, inlineImages);
        return replaceLegacyUbbEmoticons(value, inlineImages);
    }

    private static String replaceNamedUbbEmoticons(String content, List<Post.InlineImage> inlineImages) {
        Matcher matcher = UBB_EMOTICON.matcher(content);
        StringBuffer buffer = new StringBuffer();
        while (matcher.find()) {
            String source = emoticonUrlFor(matcher.group(1), matcher.group(2));
            if (source.isEmpty()) {
                matcher.appendReplacement(buffer, Matcher.quoteReplacement(matcher.group()));
                continue;
            }
            String label = matcher.group();
            addInlineImage(inlineImages, source, label);
            matcher.appendReplacement(buffer, Matcher.quoteReplacement(" " + label + " "));
        }
        matcher.appendTail(buffer);
        return buffer.toString();
    }

    private static String replaceLegacyUbbEmoticons(String content, List<Post.InlineImage> inlineImages) {
        Matcher matcher = LEGACY_EMOTICON.matcher(content);
        StringBuffer buffer = new StringBuffer();
        while (matcher.find()) {
            String source = EMOTICON_BASE_URL + "ac" + matcher.group(1) + ".png";
            String label = matcher.group();
            addInlineImage(inlineImages, source, label);
            matcher.appendReplacement(buffer, Matcher.quoteReplacement(" " + label + " "));
        }
        matcher.appendTail(buffer);
        return buffer.toString();
    }

    private static void addInlineImage(List<Post.InlineImage> inlineImages, String source, String label) {
        if (source == null || source.trim().isEmpty() || label == null || label.trim().isEmpty()) {
            return;
        }
        String normalizedSource = source.trim();
        String normalizedLabel = label.trim();
        for (Post.InlineImage inlineImage : inlineImages) {
            if (normalizedSource.equals(inlineImage.sourceUrl)
                    && normalizedLabel.equals(inlineImage.label)) {
                return;
            }
        }
        inlineImages.add(new Post.InlineImage(normalizedSource, normalizedLabel));
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

    private static String emoticonUrlFor(String group, String code) {
        String fileName = emoticonFileFor(group, code);
        return fileName.isEmpty() ? "" : EMOTICON_BASE_URL + fileName;
    }

    private static String emoticonFileFor(String group, String code) {
        String normalizedGroup = group == null ? "" : group.trim().toLowerCase(Locale.ROOT);
        String normalizedCode = code == null ? "" : code.trim();
        if (normalizedGroup.isEmpty() || normalizedCode.isEmpty()) {
            return "";
        }

        String exact = ubbEmoticonFiles().get(normalizedGroup + ":" + normalizedCode);
        if (exact != null && !exact.isEmpty()) {
            return exact;
        }

        if (!normalizedCode.matches("\\d{1,3}")) {
            return "";
        }
        int numeric = Integer.parseInt(normalizedCode);
        if ("ac".equals(normalizedGroup)) {
            return "ac" + numeric + ".png";
        }
        if ("a2".equals(normalizedGroup)) {
            return "a2_" + twoDigit(numeric) + ".png";
        }
        if ("ng".equals(normalizedGroup)) {
            return "ng_" + twoDigit(numeric) + ".png";
        }
        return "";
    }

    private static Map<String, String> ubbEmoticonFiles() {
        Map<String, String> files = new HashMap<>();
        putEmoticon(files, "ac", "blink", "ac0.png");
        putEmoticon(files, "ac", "goodjob", "ac1.png");
        putEmoticon(files, "ac", "上", "ac2.png");
        putEmoticon(files, "ac", "中枪", "ac3.png");
        putEmoticon(files, "ac", "偷笑", "ac4.png");
        putEmoticon(files, "ac", "冷", "ac5.png");
        putEmoticon(files, "ac", "凌乱", "ac6.png");
        putEmoticon(files, "ac", "反对", "ac7.png");
        putEmoticon(files, "ac", "吓", "ac8.png");
        putEmoticon(files, "ac", "吻", "ac9.png");
        putEmoticon(files, "ac", "呆", "ac10.png");
        putEmoticon(files, "ac", "咦", "ac11.png");
        putEmoticon(files, "ac", "哦", "ac12.png");
        putEmoticon(files, "ac", "哭", "ac13.png");
        putEmoticon(files, "ac", "哭1", "ac14.png");
        putEmoticon(files, "ac", "哭笑", "ac15.png");
        putEmoticon(files, "ac", "哼", "ac16.png");
        putEmoticon(files, "ac", "喘", "ac17.png");
        putEmoticon(files, "ac", "喷", "ac18.png");
        putEmoticon(files, "ac", "嘲笑", "ac19.png");
        putEmoticon(files, "ac", "嘲笑1", "ac20.png");
        putEmoticon(files, "ac", "囧", "ac21.png");
        putEmoticon(files, "ac", "委屈", "ac22.png");
        putEmoticon(files, "ac", "心", "ac23.png");
        putEmoticon(files, "ac", "忧伤", "ac24.png");
        putEmoticon(files, "ac", "怒", "ac25.png");
        putEmoticon(files, "ac", "怕", "ac26.png");
        putEmoticon(files, "ac", "惊", "ac27.png");
        putEmoticon(files, "ac", "愁", "ac28.png");
        putEmoticon(files, "ac", "抓狂", "ac29.png");
        putEmoticon(files, "ac", "抠鼻", "ac30.png");
        putEmoticon(files, "ac", "擦汗", "ac31.png");
        putEmoticon(files, "ac", "无语", "ac32.png");
        putEmoticon(files, "ac", "晕", "ac33.png");
        putEmoticon(files, "ac", "汗", "ac34.png");
        putEmoticon(files, "ac", "瞎", "ac35.png");
        putEmoticon(files, "ac", "羞", "ac36.png");
        putEmoticon(files, "ac", "羡慕", "ac37.png");
        putEmoticon(files, "ac", "花痴", "ac38.png");
        putEmoticon(files, "ac", "茶", "ac39.png");
        putEmoticon(files, "ac", "衰", "ac40.png");
        putEmoticon(files, "ac", "计划通", "ac41.png");
        putEmoticon(files, "ac", "赞同", "ac42.png");
        putEmoticon(files, "ac", "闪光", "ac43.png");
        putEmoticon(files, "ac", "黑枪", "ac44.png");

        putEmoticon(files, "a2", "goodjob", "a2_02.png");
        putEmoticon(files, "a2", "诶嘿", "a2_05.png");
        putEmoticon(files, "a2", "偷笑", "a2_03.png");
        putEmoticon(files, "a2", "怒", "a2_04.png");
        putEmoticon(files, "a2", "笑", "a2_07.png");
        putEmoticon(files, "a2", "那个…", "a2_08.png");
        putEmoticon(files, "a2", "哦嗬嗬嗬", "a2_09.png");
        putEmoticon(files, "a2", "舔", "a2_10.png");
        putEmoticon(files, "a2", "鬼脸", "a2_14.png");
        putEmoticon(files, "a2", "冷", "a2_16.png");
        putEmoticon(files, "a2", "大哭", "a2_15.png");
        putEmoticon(files, "a2", "哭", "a2_17.png");
        putEmoticon(files, "a2", "恨", "a2_21.png");
        putEmoticon(files, "a2", "中枪", "a2_23.png");
        putEmoticon(files, "a2", "囧", "a2_24.png");
        putEmoticon(files, "a2", "你看看你", "a2_25.png");
        putEmoticon(files, "a2", "doge", "a2_27.png");
        putEmoticon(files, "a2", "自戳双目", "a2_28.png");
        putEmoticon(files, "a2", "偷吃", "a2_30.png");
        putEmoticon(files, "a2", "冷笑", "a2_31.png");
        putEmoticon(files, "a2", "壁咚", "a2_32.png");
        putEmoticon(files, "a2", "不活了", "a2_33.png");
        putEmoticon(files, "a2", "不明觉厉", "a2_36.png");
        putEmoticon(files, "a2", "是在下输了", "a2_51.png");
        putEmoticon(files, "a2", "你为猴这么", "a2_53.png");
        putEmoticon(files, "a2", "干杯", "a2_54.png");
        putEmoticon(files, "a2", "干杯2", "a2_55.png");
        putEmoticon(files, "a2", "异议", "a2_47.png");
        putEmoticon(files, "a2", "认真", "a2_48.png");
        putEmoticon(files, "a2", "你已经死了", "a2_45.png");
        putEmoticon(files, "a2", "你这种人…", "a2_49.png");
        putEmoticon(files, "a2", "妮可妮可妮", "a2_18.png");
        putEmoticon(files, "a2", "惊", "a2_19.png");
        putEmoticon(files, "a2", "抢镜头", "a2_52.png");
        putEmoticon(files, "a2", "yes", "a2_26.png");
        putEmoticon(files, "a2", "有何贵干", "a2_11.png");
        putEmoticon(files, "a2", "病娇", "a2_12.png");
        putEmoticon(files, "a2", "lucky", "a2_13.png");
        putEmoticon(files, "a2", "poi", "a2_20.png");
        putEmoticon(files, "a2", "囧2", "a2_22.png");
        putEmoticon(files, "a2", "威吓", "a2_42.png");
        putEmoticon(files, "a2", "jojo立", "a2_37.png");
        putEmoticon(files, "a2", "jojo立2", "a2_38.png");
        putEmoticon(files, "a2", "jojo立3", "a2_39.png");
        putEmoticon(files, "a2", "jojo立4", "a2_41.png");
        putEmoticon(files, "a2", "jojo立5", "a2_40.png");

        putEmoticon(files, "ng", "呲牙笑", "ng_1.png");
        putEmoticon(files, "ng", "奸笑", "ng_2.png");
        putEmoticon(files, "ng", "问号", "ng_3.png");
        putEmoticon(files, "ng", "茶", "ng_4.png");
        putEmoticon(files, "ng", "笑指", "ng_5.png");
        putEmoticon(files, "ng", "燃尽", "ng_6.png");
        putEmoticon(files, "ng", "晕", "ng_7.png");
        putEmoticon(files, "ng", "扇笑", "ng_8.png");
        putEmoticon(files, "ng", "寄", "ng_9.png");
        putEmoticon(files, "ng", "别急", "ng_10.png");
        putEmoticon(files, "ng", "doge", "ng_11.png");
        putEmoticon(files, "ng", "丧", "ng_12.png");
        putEmoticon(files, "ng", "汗", "ng_13.png");
        putEmoticon(files, "ng", "叹气", "ng_15.png");
        putEmoticon(files, "ng", "吃饼", "ng_16.png");
        putEmoticon(files, "ng", "吃瓜", "ng_17.png");
        putEmoticon(files, "ng", "吐舌", "ng_18.png");
        putEmoticon(files, "ng", "哭", "ng_19.png");
        putEmoticon(files, "ng", "喘", "ng_20.png");
        putEmoticon(files, "ng", "心", "ng_21.png");
        putEmoticon(files, "ng", "喷", "ng_22.png");
        putEmoticon(files, "ng", "困", "ng_24.png");
        putEmoticon(files, "ng", "大哭", "ng_25.png");
        putEmoticon(files, "ng", "大惊", "ng_26.png");
        putEmoticon(files, "ng", "害怕", "ng_27.png");
        putEmoticon(files, "ng", "惊", "ng_28.png");
        putEmoticon(files, "ng", "暴怒", "ng_30.png");
        putEmoticon(files, "ng", "气愤", "ng_31.png");
        putEmoticon(files, "ng", "热", "ng_32.png");
        putEmoticon(files, "ng", "瓜不熟", "ng_33.png");
        putEmoticon(files, "ng", "瞎", "ng_34.png");
        putEmoticon(files, "ng", "色", "ng_35.png");
        putEmoticon(files, "ng", "斜眼", "ng_37.png");
        putEmoticon(files, "ng", "问号大", "ng_38.png");

        putEmoticon(files, "pg", "战斗力", "pg01.png");
        putEmoticon(files, "pg", "哈啤", "pg02.png");
        putEmoticon(files, "pg", "满分", "pg03.png");
        putEmoticon(files, "pg", "衰", "pg04.png");
        putEmoticon(files, "pg", "拒绝", "pg05.png");
        putEmoticon(files, "pg", "心", "pg06.png");
        putEmoticon(files, "pg", "严肃", "pg07.png");
        putEmoticon(files, "pg", "吃瓜", "pg08.png");
        putEmoticon(files, "pg", "嘣", "pg09.png");
        putEmoticon(files, "pg", "嘣2", "pg10.png");
        putEmoticon(files, "pg", "冻", "pg11.png");
        putEmoticon(files, "pg", "谢", "pg12.png");
        putEmoticon(files, "pg", "哭", "pg13.png");
        putEmoticon(files, "pg", "响指", "pg14.png");
        putEmoticon(files, "pg", "转身", "pg15.png");
        return files;
    }

    private static void putEmoticon(Map<String, String> files, String group, String code, String fileName) {
        files.put(group + ":" + code, fileName);
    }

    private static String twoDigit(int value) {
        return value < 10 ? "0" + value : String.valueOf(value);
    }

    private static boolean isInlineEmoticonImageUrl(String url) {
        if (url == null) {
            return false;
        }
        String value = url.toLowerCase(Locale.ROOT);
        return value.contains("/ngabbs/post/smile/")
                || value.contains("/post/smile/")
                || value.contains("/smiley/")
                || value.contains("/emoticon/");
    }

    private static String labelForNgaSmileUrl(String url) {
        String value = url == null ? "" : url.trim().toLowerCase(Locale.ROOT);
        int slash = value.lastIndexOf('/');
        String fileName = slash >= 0 ? value.substring(slash + 1) : value;
        if ("ac15.png".equals(fileName)) {
            return "[s:ac:哭笑]";
        }
        if ("ng_11.png".equals(fileName)) {
            return "[s:ng:doge]";
        }
        if ("a2_27.png".equals(fileName)) {
            return "[s:a2:doge]";
        }
        return "[表情]";
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
                || value.contains("/post/smile/")
                || value.contains("/ngabbs/post/smile/")
                || value.contains("emoticon")) {
            return "";
        }
        int bracket = value.indexOf('[');
        if (bracket >= 0) {
            value = value.substring(0, bracket).trim();
        }
        if (isInlineEmoticonImageUrl(value)) {
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
        value = QUOTE_BLOCK.matcher(value).replaceAll("\n");
        value = PID_REPLY.matcher(value).replaceAll("\n");
        value = BBCODE_IMAGE.matcher(value).replaceAll("\n");
        value = replaceHtmlImageLabels(value);
        value = replaceMatchWithGroup(value, BBCODE_URL, 1);
        value = replaceMatchWithGroup(value, BBCODE_UID, 1);
        value = normalizeApiLineBreaks(value);
        value = BBCODE_SIMPLE_TAG.matcher(value).replaceAll(" ");
        value = Parser.unescapeEntities(value, false);
        value = normalizeApiLineBreaks(value);
        value = LEADING_REPLY_QUOTE_BLOCK.matcher(value).replaceAll("\n");
        value = REPLY_TO_HEADER.matcher(value).replaceAll("\n");
        value = POST_BY_HEADER.matcher(value).replaceAll(" ");
        return value.replace('\u00a0', ' ')
                .replace("[/url]", " ")
                .replace("[url]", " ")
                .replaceAll("<[^>]+>", " ")
                .replaceAll("(?m)(^|\\s)>\\s+", "$1")
                .replace("\r\n", "\n")
                .replace('\r', '\n')
                .replaceAll("[ \\t\\x0B\\f]+", " ")
                .replaceAll("[ \\t]*\\n[ \\t]*", "\n")
                .replaceAll("\\n{2,}", "\n")
                .replaceAll("[ \\t]+", " ")
                .trim();
    }

    private static String normalizeApiLineBreaks(String value) {
        String normalized = API_BR.matcher(value).replaceAll("\n");
        normalized = HTML_LINE_BREAK.matcher(normalized).replaceAll("\n");
        normalized = HTML_ADJACENT_BLOCK_BOUNDARY.matcher(normalized).replaceAll("\n");
        return HTML_BLOCK_BOUNDARY.matcher(normalized).replaceAll("\n");
    }

    private static String replaceMatchWithGroup(String value, Pattern pattern, int group) {
        Matcher matcher = pattern.matcher(value);
        StringBuffer buffer = new StringBuffer();
        while (matcher.find()) {
            matcher.appendReplacement(buffer, Matcher.quoteReplacement(matcher.group(group)));
        }
        matcher.appendTail(buffer);
        return buffer.toString();
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
                        + " " + image.attr("zoomfile")
                        + " " + image.attr("class")
                        + " " + image.attr("alt")
                        + " " + image.attr("title")
        ).toLowerCase(Locale.ROOT);
        boolean emoticon = value.contains("/smiley/")
                || value.contains("/post/smile/")
                || value.contains("/ngabbs/post/smile/")
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

    private static final class ParsedInlineContent {
        final String content;
        final List<Post.InlineImage> inlineImages;

        ParsedInlineContent(String content, List<Post.InlineImage> inlineImages) {
            this.content = content;
            this.inlineImages = inlineImages;
        }
    }
}
