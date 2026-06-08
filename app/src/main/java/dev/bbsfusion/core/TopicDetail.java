package dev.bbsfusion.core;

import java.util.Collections;
import java.util.List;

public final class TopicDetail {
    public final String title;
    public final String url;
    public final List<Post> posts;

    public TopicDetail(String title, String url, List<Post> posts) {
        this.title = title;
        this.url = url;
        this.posts = Collections.unmodifiableList(posts);
    }
}
