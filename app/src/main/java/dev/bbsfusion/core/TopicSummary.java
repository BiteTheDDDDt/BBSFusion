package dev.bbsfusion.core;

public final class TopicSummary {
    public final String siteId;
    public final String title;
    public final String url;
    public final String meta;
    public final long sortTimeMillis;

    public TopicSummary(String siteId, String title, String url, String meta) {
        this(siteId, title, url, meta, 0L);
    }

    public TopicSummary(String siteId, String title, String url, String meta, long sortTimeMillis) {
        this.siteId = siteId;
        this.title = title;
        this.url = url;
        this.meta = meta;
        this.sortTimeMillis = sortTimeMillis;
    }
}
