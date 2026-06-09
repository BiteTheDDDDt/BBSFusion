package dev.bbsfusion.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class BoardCatalog {
    private static final String S1_REFERRER = "https://stage1st.com/2b/";
    private static final String NGA_REFERRER = "https://bbs.nga.cn/";
    private static final String V2EX_REFERRER = "https://www.v2ex.com/";
    private static final String LINUXDO_REFERRER = "https://linux.do/";

    private static final List<BoardDefinition> BUILT_IN_BOARDS = new ArrayList<>();
    private static final List<BoardDefinition> DEFAULT_GROUP_BOARDS = new ArrayList<>();

    static {
        BoardDefinition s1Zhuominggu = s1("157", "卓明谷", "https://stage1st.com/2b/forum-157-1.html");
        BoardDefinition ngaWater = nga("-7", "大漩涡");
        DEFAULT_GROUP_BOARDS.add(s1Zhuominggu);
        DEFAULT_GROUP_BOARDS.add(ngaWater);

        BUILT_IN_BOARDS.add(s1Zhuominggu);
        BUILT_IN_BOARDS.add(s1("4", "游戏论坛"));
        BUILT_IN_BOARDS.add(s1("6", "动漫论坛"));
        BUILT_IN_BOARDS.add(s1("135", "手游页游"));
        BUILT_IN_BOARDS.add(s1("151", "VTB"));
        BUILT_IN_BOARDS.add(s1("77", "八卦体育"));
        BUILT_IN_BOARDS.add(s1("136", "模玩手办"));
        BUILT_IN_BOARDS.add(s1("48", "影视论坛"));
        BUILT_IN_BOARDS.add(s1("51", "PC 数码"));
        BUILT_IN_BOARDS.add(s1("144", "欧美动漫"));
        BUILT_IN_BOARDS.add(s1("83", "动漫鉴赏区"));
        BUILT_IN_BOARDS.add(s1("74", "马叉虫"));

        BUILT_IN_BOARDS.add(ngaWater);
        BUILT_IN_BOARDS.add(nga("414", "游戏综合讨论"));
        BUILT_IN_BOARDS.add(nga("300", "网络游戏综合"));
        BUILT_IN_BOARDS.add(nga("428", "手机/网页游戏综合"));
        BUILT_IN_BOARDS.add(nga("334", "PC 软硬件"));
        BUILT_IN_BOARDS.add(nga("436", "消费电子 IT 新闻"));
        BUILT_IN_BOARDS.add(nga("706", "大时代"));
        BUILT_IN_BOARDS.add(nga("616", "Nintendo 游戏综合"));
        BUILT_IN_BOARDS.add(nga("591", "格斗游戏综合"));
        BUILT_IN_BOARDS.add(nga("482", "CS:GO"));
        BUILT_IN_BOARDS.add(nga("708", "无畏契约"));
        BUILT_IN_BOARDS.add(nga("640", "Apex 英雄"));
        BUILT_IN_BOARDS.add(nga("459", "守望先锋"));
        BUILT_IN_BOARDS.add(nga("422", "炉石传说"));
        BUILT_IN_BOARDS.add(nga("489", "怪物猎人(Capcom)"));
        BUILT_IN_BOARDS.add(nga("650", "原神"));
        BUILT_IN_BOARDS.add(nga("540", "Fate/Grand Order"));
        BUILT_IN_BOARDS.add(nga("310", "精英议会"));
        BUILT_IN_BOARDS.add(nga("182", "魔法圣堂"));
        BUILT_IN_BOARDS.add(nga("183", "信仰神殿"));
        BUILT_IN_BOARDS.add(nga("186", "翡翠梦境"));
        BUILT_IN_BOARDS.add(nga("441", "战舰世界"));
        BUILT_IN_BOARDS.add(nga("443", "EAFC 系列"));
        BUILT_IN_BOARDS.add(nga("707", "冒险岛"));

        BUILT_IN_BOARDS.add(v2ex("latest", "最新", "https://www.v2ex.com/recent"));
        BUILT_IN_BOARDS.add(v2ex("programmer", "程序员"));
        BUILT_IN_BOARDS.add(v2ex("python", "Python"));
        BUILT_IN_BOARDS.add(v2ex("go", "Go"));
        BUILT_IN_BOARDS.add(v2ex("android", "Android"));
        BUILT_IN_BOARDS.add(v2ex("create", "分享创造"));
        BUILT_IN_BOARDS.add(v2ex("qna", "问与答"));
        BUILT_IN_BOARDS.add(v2ex("jobs", "酷工作"));
        BUILT_IN_BOARDS.add(v2ex("apple", "Apple"));

        BUILT_IN_BOARDS.add(linuxdo("latest", "最新", "https://linux.do/latest"));
        BUILT_IN_BOARDS.add(linuxdoCategory("develop", "4", "开发调优"));
        BUILT_IN_BOARDS.add(linuxdoCategory("resource", "14", "资源荟萃"));
        BUILT_IN_BOARDS.add(linuxdoCategory("news", "34", "前沿快讯"));
        BUILT_IN_BOARDS.add(linuxdoCategory("welfare", "36", "福利羊毛"));
        BUILT_IN_BOARDS.add(linuxdoCategory("gossip", "11", "搞七捻三"));
        BUILT_IN_BOARDS.add(linuxdoCategory("job", "27", "非我莫属"));
    }

    private BoardCatalog() {
    }

    public static List<BoardDefinition> builtInBoards() {
        return new ArrayList<>(BUILT_IN_BOARDS);
    }

    public static BoardDefinition defaultBoardForSite(String siteId) {
        for (BoardDefinition board : BUILT_IN_BOARDS) {
            if (board.siteId.equals(siteId)) {
                return board;
            }
        }
        throw new IllegalArgumentException("Unknown site: " + siteId);
    }

    public static List<BoardDefinition> defaultGroupBoards() {
        return new ArrayList<>(DEFAULT_GROUP_BOARDS);
    }

    public static List<BoardDefinition> defaultS1GroupBoards() {
        List<BoardDefinition> boards = new ArrayList<>();
        addBoardById(boards, "s1", "157");
        addBoardById(boards, "s1", "4");
        addBoardById(boards, "s1", "6");
        return boards;
    }

    public static List<BoardDefinition> defaultNgaGroupBoards() {
        List<BoardDefinition> boards = new ArrayList<>();
        addBoardById(boards, "nga", "-7");
        addBoardById(boards, "nga", "414");
        addBoardById(boards, "nga", "706");
        return boards;
    }

    public static List<BoardDefinition> defaultV2exGroupBoards() {
        List<BoardDefinition> boards = new ArrayList<>();
        addBoardById(boards, "v2ex", "latest");
        return boards;
    }

    public static List<BoardDefinition> defaultLinuxDoGroupBoards() {
        List<BoardDefinition> boards = new ArrayList<>();
        addBoardById(boards, "linuxdo", "latest");
        return boards;
    }

    public static List<BoardDefinition> merge(List<BoardDefinition> first, List<BoardDefinition> second) {
        Map<String, BoardDefinition> boards = new LinkedHashMap<>();
        for (BoardDefinition board : first) {
            boards.put(board.key(), board);
        }
        for (BoardDefinition board : second) {
            if (!boards.containsKey(board.key())) {
                boards.put(board.key(), board);
            }
        }
        return new ArrayList<>(boards.values());
    }

    private static BoardDefinition s1(String boardId, String title) {
        return s1(boardId, title, "https://stage1st.com/2b/forum-" + boardId + "-1.html");
    }

    private static BoardDefinition s1(String boardId, String title, String url) {
        return new BoardDefinition("s1", boardId, title, url, S1_REFERRER, "S1 " + title);
    }

    private static void addBoardById(List<BoardDefinition> boards, String siteId, String boardId) {
        for (BoardDefinition board : BUILT_IN_BOARDS) {
            if (board.siteId.equals(siteId) && board.boardId.equals(boardId)) {
                boards.add(board);
                return;
            }
        }
    }

    private static BoardDefinition nga(String boardId, String title) {
        return new BoardDefinition(
                "nga",
                boardId,
                title,
                "https://bbs.nga.cn/thread.php?fid=" + boardId,
                NGA_REFERRER,
                "NGA " + title
        );
    }

    private static BoardDefinition v2ex(String boardId, String title) {
        return v2ex(boardId, title, "https://www.v2ex.com/go/" + boardId);
    }

    private static BoardDefinition v2ex(String boardId, String title, String url) {
        return new BoardDefinition("v2ex", boardId, title, url, V2EX_REFERRER, "V2EX " + title);
    }

    private static BoardDefinition linuxdo(String boardId, String title, String url) {
        return new BoardDefinition("linuxdo", boardId, title, url, LINUXDO_REFERRER, "Linux.do " + title);
    }

    private static BoardDefinition linuxdoCategory(String slug, String categoryId, String title) {
        return linuxdo(
                "c:" + slug + ":" + categoryId,
                title,
                "https://linux.do/c/" + slug + "/" + categoryId
        );
    }
}
