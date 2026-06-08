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

public final class S1Connector implements ForumConnector {
    private static final String HOME_URL =
            "https://stage1st.com/2b/forum-157-1.html";
    private static final String LOGIN_URL =
            "https://stage1st.com/2b/member.php?mod=logging&action=login&mobile=2";

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
        Document document = NetworkClient.getDesktop(board.url, board.referrer);
        return ForumHtmlParsers.extractTopics(document, id(), board.url, board.sourceLabel);
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
        Document document = NetworkClient.get(url, HOME_URL);
        return ForumHtmlParsers.extractTopic(document, url);
    }
}
