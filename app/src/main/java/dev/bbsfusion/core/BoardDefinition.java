package dev.bbsfusion.core;

import org.json.JSONException;
import org.json.JSONObject;

public final class BoardDefinition {
    public final String siteId;
    public final String boardId;
    public final String title;
    public final String url;
    public final String referrer;
    public final String sourceLabel;

    public BoardDefinition(
            String siteId,
            String boardId,
            String title,
            String url,
            String referrer,
            String sourceLabel
    ) {
        this.siteId = siteId;
        this.boardId = boardId;
        this.title = title;
        this.url = url;
        this.referrer = referrer;
        this.sourceLabel = sourceLabel;
    }

    public String key() {
        return siteId + ":" + boardId;
    }

    public JSONObject toJson() throws JSONException {
        JSONObject json = new JSONObject();
        json.put("siteId", siteId);
        json.put("boardId", boardId);
        json.put("title", title);
        json.put("url", url);
        json.put("referrer", referrer);
        json.put("sourceLabel", sourceLabel);
        return json;
    }

    public static BoardDefinition fromJson(JSONObject json) throws JSONException {
        String siteId = json.getString("siteId");
        String boardId = json.getString("boardId");
        String title = json.getString("title");
        String url = json.getString("url");
        String referrer = json.optString("referrer", url);
        String sourceLabel = json.optString("sourceLabel", siteId.toUpperCase() + " " + title);
        return new BoardDefinition(siteId, boardId, title, url, referrer, sourceLabel);
    }
}
