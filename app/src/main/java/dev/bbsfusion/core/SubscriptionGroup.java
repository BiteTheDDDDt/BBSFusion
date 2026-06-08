package dev.bbsfusion.core;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

public final class SubscriptionGroup {
    public final String id;
    public final String name;
    public final List<BoardDefinition> boards;

    public SubscriptionGroup(String id, String name, List<BoardDefinition> boards) {
        this.id = id;
        this.name = name;
        this.boards = new ArrayList<>(boards);
    }

    public JSONObject toJson() throws JSONException {
        JSONObject json = new JSONObject();
        json.put("id", id);
        json.put("name", name);

        JSONArray boardArray = new JSONArray();
        for (BoardDefinition board : boards) {
            boardArray.put(board.toJson());
        }
        json.put("boards", boardArray);
        return json;
    }

    public static SubscriptionGroup fromJson(JSONObject json) throws JSONException {
        String id = json.getString("id");
        String name = json.getString("name");
        JSONArray boardArray = json.optJSONArray("boards");
        List<BoardDefinition> boards = new ArrayList<>();
        if (boardArray != null) {
            for (int i = 0; i < boardArray.length(); i++) {
                boards.add(BoardDefinition.fromJson(boardArray.getJSONObject(i)));
            }
        }
        return new SubscriptionGroup(id, name, boards);
    }
}
