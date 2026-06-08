package dev.bbsfusion.core;

import java.io.IOException;
import java.util.List;

public interface ForumConnector {
    String id();

    String name();

    String homeUrl();

    String loginUrl();

    List<TopicSummary> fetchTopics() throws IOException;

    List<TopicSummary> fetchTopics(BoardDefinition board) throws IOException;

    List<BoardDefinition> fetchAvailableBoards() throws IOException;

    TopicDetail fetchTopic(String url) throws IOException;
}
