package dev.bbsfusion.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class Post {
    public final String author;
    public final String avatarUrl;
    public final String meta;
    public final String content;
    public final List<String> imageUrls;

    public Post(String author, String content) {
        this(author, "", content);
    }

    public Post(String author, String avatarUrl, String content) {
        this(author, avatarUrl, content, Collections.emptyList());
    }

    public Post(String author, String avatarUrl, String content, List<String> imageUrls) {
        this(author, avatarUrl, "", content, imageUrls);
    }

    public Post(String author, String avatarUrl, String meta, String content, List<String> imageUrls) {
        this.author = author;
        this.avatarUrl = avatarUrl == null ? "" : avatarUrl;
        this.meta = meta == null ? "" : meta.trim();
        this.content = content;
        if (imageUrls == null || imageUrls.isEmpty()) {
            this.imageUrls = Collections.emptyList();
        } else {
            this.imageUrls = Collections.unmodifiableList(new ArrayList<>(imageUrls));
        }
    }
}
