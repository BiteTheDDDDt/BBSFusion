package dev.bbsfusion.core;

import java.util.Collections;
import java.util.List;

public final class TopicDetail {
    public final String title;
    public final String url;
    public final List<Post> posts;
    public final int pageNumber;
    public final boolean hasMore;
    /** Zero means the site did not supply a reliable page count. */
    public final int lastPage;
    /** Zero means floor numbers cannot be mapped to page numbers arithmetically. */
    public final int pageSize;

    public TopicDetail(String title, String url, List<Post> posts) {
        this(title, url, posts, 1, false);
    }

    public TopicDetail(String title, String url, List<Post> posts, int pageNumber, boolean hasMore) {
        this(title, url, posts, pageNumber, hasMore, 0, 0);
    }

    public TopicDetail(String title, String url, List<Post> posts, int pageNumber, boolean hasMore,
            int lastPage, int pageSize) {
        this.title = title;
        this.url = url;
        this.posts = Collections.unmodifiableList(posts);
        this.pageNumber = Math.max(1, pageNumber);
        this.hasMore = hasMore;
        this.lastPage = lastPage >= this.pageNumber && (!hasMore || lastPage > this.pageNumber)
                ? lastPage : 0;
        this.pageSize = Math.max(0, pageSize);
    }
}
