package dev.bbsfusion.core;

import java.util.Collections;
import java.util.List;

public final class TopicDetail {
    public final String title;
    public final String url;
    public final List<Post> posts;
    public final int pageNumber;
    public final boolean hasMore;

    public TopicDetail(String title, String url, List<Post> posts) {
        this(title, url, posts, 1, false);
    }

    public TopicDetail(String title, String url, List<Post> posts, int pageNumber, boolean hasMore) {
        this.title = title;
        this.url = url;
        this.posts = Collections.unmodifiableList(posts);
        this.pageNumber = Math.max(1, pageNumber);
        this.hasMore = hasMore;
    }
}
