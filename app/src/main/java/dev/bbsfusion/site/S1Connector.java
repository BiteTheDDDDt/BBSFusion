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
        return fetchTopics(board, 1);
    }

    @Override
    public List<TopicSummary> fetchTopics(BoardDefinition board, int page) throws IOException {
        String pageUrl = pagedBoardUrl(board.url, page);
        Document document = NetworkClient.getDesktop(pageUrl, board.referrer);
        return ForumHtmlParsers.extractTopics(document, id(), pageUrl, board.sourceLabel);
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

    static String pagedTopicUrl(String url, int page) {
        return pagedUrl(url, page, THREAD_PAGE_URL);
    }

    static String pagedBoardUrl(String url, int page) {
        return pagedUrl(url, page, BOARD_PAGE_URL);
    }

    private static String pagedUrl(String url, int page, Pattern pagePattern) {
        if (url == null || url.isEmpty()) {
            return url;
        }
        int fragment = url.indexOf('#');
        String base = fragment >= 0 ? url.substring(0, fragment) : url;
        int pageNumber = Math.max(1, page);
        Matcher matcher = pagePattern.matcher(base);
        if (matcher.find()) {
            return matcher.replaceFirst(Matcher.quoteReplacement(
                    matcher.group(1) + pageNumber + matcher.group(2)
            ));
        }
        Matcher queryPage = Pattern.compile("([?&])page=[^&]*").matcher(base);
        if (queryPage.find()) {
            return queryPage.replaceFirst(Matcher.quoteReplacement(queryPage.group(1) + "page=" + pageNumber));
        }
        return pageNumber <= 1 ? base : base + (base.contains("?") ? "&" : "?") + "page=" + pageNumber;
    }

}
