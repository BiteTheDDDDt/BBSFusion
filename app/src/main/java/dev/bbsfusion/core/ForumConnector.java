package dev.bbsfusion.core;

import java.io.IOException;
import java.util.Collections;
import java.util.List;

public interface ForumConnector {
    String id();

    String name();

    String homeUrl();

    String loginUrl();

    List<TopicSummary> fetchTopics() throws IOException;

    List<TopicSummary> fetchTopics(BoardDefinition board) throws IOException;

    default List<TopicSummary> fetchTopics(BoardDefinition board, int page) throws IOException {
        if (page <= 1) {
            return fetchTopics(board);
        }
        return Collections.emptyList();
    }

    List<BoardDefinition> fetchAvailableBoards() throws IOException;

    TopicDetail fetchTopic(String url) throws IOException;

    default TopicDetail fetchTopicPage(String url, int page) throws IOException {
        if (page <= 1) {
            return fetchTopic(url);
        }
        return new TopicDetail("帖子详情", url, java.util.Collections.emptyList(), page, false);
    }
}
