package dev.bbsfusion.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class Post {
    public final String author;
    public final String avatarUrl;
    public final String meta;
    public final String postedMeta;
    public final String editedMeta;
    public final String replyContext;
    public final String content;
    public final List<String> imageUrls;
    public final List<InlineImage> inlineImages;

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
        this(author, avatarUrl, meta, "", content, imageUrls, Collections.emptyList());
    }

    public Post(
            String author,
            String avatarUrl,
            String meta,
            String replyContext,
            String content,
            List<String> imageUrls,
            List<InlineImage> inlineImages
    ) {
        this.author = author;
        this.avatarUrl = avatarUrl == null ? "" : avatarUrl;
        this.meta = meta == null ? "" : meta.trim();
        this.postedMeta = prefixedMeta(this.meta, "发表于");
        this.editedMeta = prefixedMeta(this.meta, "编辑");
        this.replyContext = replyContext == null ? "" : replyContext.trim();
        this.content = content == null ? "" : content;
        if (imageUrls == null || imageUrls.isEmpty()) {
            this.imageUrls = Collections.emptyList();
        } else {
            this.imageUrls = Collections.unmodifiableList(new ArrayList<>(imageUrls));
        }
        if (inlineImages == null || inlineImages.isEmpty()) {
            this.inlineImages = Collections.emptyList();
        } else {
            this.inlineImages = Collections.unmodifiableList(new ArrayList<>(inlineImages));
        }
    }

    private static String prefixedMeta(String meta, String prefix) {
        if (meta == null || meta.trim().isEmpty()) {
            return "";
        }
        String[] parts = meta.split("\\s*·\\s*");
        for (String part : parts) {
            String value = part.trim();
            if (value.startsWith(prefix)) {
                return value;
            }
        }
        return "";
    }

    public static final class InlineImage {
        public final String sourceUrl;
        public final String label;

        public InlineImage(String sourceUrl, String label) {
            this.sourceUrl = sourceUrl == null ? "" : sourceUrl.trim();
            this.label = label == null ? "" : label.trim();
        }
    }
}
