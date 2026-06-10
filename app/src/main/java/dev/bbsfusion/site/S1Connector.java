package dev.bbsfusion.site;

import dev.bbsfusion.core.BoardCatalog;
import dev.bbsfusion.core.BoardDefinition;
import dev.bbsfusion.core.ForumConnector;
import dev.bbsfusion.core.TopicDetail;
import dev.bbsfusion.core.TopicSummary;

import org.jsoup.nodes.Document;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class S1Connector implements ForumConnector {
    private static final int TOPIC_LIST_PAGE_LIMIT = 4;
    private static final int TOPIC_LIST_LIMIT = 80;
    private static final String HOME_URL =
            "https://stage1st.com/2b/forum-157-1.html";
    private static final String LOGIN_URL =
            "https://stage1st.com/2b/member.php?mod=logging&action=login&mobile=2";
    private static final Pattern BOARD_PAGE_URL =
            Pattern.compile("(forum--?\\d+-)\\d+(\\.html)");
    private static final Pattern THREAD_PAGE_URL =
            Pattern.compile("(thread-\\d+-)\\d+(-\\d+\\.html)");

    @Override
    public String id() {
        return "s1";
    }

    @Override
    public String name() {
        return "S1";
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
        List<TopicSummary> topics = new ArrayList<>();
        for (int page = 1; page <= TOPIC_LIST_PAGE_LIMIT && topics.size() < TOPIC_LIST_LIMIT; page++) {
            String pageUrl = pagedBoardUrl(board.url, page);
            List<TopicSummary> pageTopics;
            try {
                Document document = NetworkClient.getDesktop(pageUrl, board.referrer);
                pageTopics = ForumHtmlParsers.extractTopics(document, id(), pageUrl, board.sourceLabel);
            } catch (IOException error) {
                if (page == 1) {
                    throw error;
                }
                break;
            }
            if (pageTopics.isEmpty()) {
                break;
            }
            int before = topics.size();
            appendUniqueTopics(topics, pageTopics);
            if (topics.size() == before || topics.size() >= TOPIC_LIST_LIMIT || pageTopics.size() < 20) {
                break;
            }
        }
        return topics;
    }

    @Override
    public List<BoardDefinition> fetchAvailableBoards() throws IOException {
        List<BoardDefinition> boards = new ArrayList<>();
        Document desktop = NetworkClient.getDesktop(
                "https://stage1st.com/2b/forum.php",
                "https://stage1st.com/2b/"
        );
        boards = BoardCatalog.merge(
                boards,
                ForumHtmlParsers.extractBoards(desktop, id(), "https://stage1st.com/2b/", name())
        );

        Document mobile = NetworkClient.get(
                "https://stage1st.com/2b/forum.php?mobile=2",
                "https://stage1st.com/2b/"
        );
        boards = BoardCatalog.merge(
                boards,
                ForumHtmlParsers.extractBoards(mobile, id(), "https://stage1st.com/2b/", name())
        );
        return boards;
    }

    @Override
    public TopicDetail fetchTopic(String url) throws IOException {
        return fetchTopicPage(url, 1);
    }

    @Override
    public TopicDetail fetchTopicPage(String url, int page) throws IOException {
        String pageUrl = pagedTopicUrl(url, page);
        Document document = NetworkClient.get(pageUrl, HOME_URL);
        return ForumHtmlParsers.extractTopic(document, pageUrl, page);
    }

    private static String pagedTopicUrl(String url, int page) {
        if (page <= 1 || url == null || url.isEmpty()) {
            return url;
        }
        Matcher matcher = THREAD_PAGE_URL.matcher(url);
        if (matcher.find()) {
            return matcher.replaceFirst(Matcher.quoteReplacement(
                    matcher.group(1) + page + matcher.group(2)
            ));
        }
        if (url.contains("page=")) {
            return url.replaceFirst("([?&]page=)\\d+", "$1" + page);
        }
        return url + (url.contains("?") ? "&" : "?") + "page=" + page;
    }

    static String pagedBoardUrl(String url, int page) {
        if (page <= 1 || url == null || url.isEmpty()) {
            return url;
        }
        Matcher matcher = BOARD_PAGE_URL.matcher(url);
        if (matcher.find()) {
            return matcher.replaceFirst(Matcher.quoteReplacement(
                    matcher.group(1) + page + matcher.group(2)
            ));
        }
        if (url.contains("page=")) {
            return url.replaceFirst("([?&]page=)\\d+", "$1" + page);
        }
        return url + (url.contains("?") ? "&" : "?") + "page=" + page;
    }

    private static void appendUniqueTopics(List<TopicSummary> target, List<TopicSummary> candidates) {
        for (TopicSummary topic : candidates) {
            if (target.size() >= TOPIC_LIST_LIMIT) {
                return;
            }
            boolean exists = false;
            for (TopicSummary existing : target) {
                if (existing.url.equals(topic.url)) {
                    exists = true;
                    break;
                }
            }
            if (!exists) {
                target.add(topic);
            }
        }
    }
}
