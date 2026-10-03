package dev.bbsfusion.core;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;

public final class BoardCatalogTest {
    @Test
    public void refreshedBoardReplacesOldMetadataWithoutChangingDirectoryOrder() {
        BoardDefinition oldBoard = board("s1", "157", "旧名称");
        BoardDefinition second = board("nga", "-7", "大漩涡");
        BoardDefinition refreshed = board("s1", "157", "新名称");

        List<BoardDefinition> merged = BoardCatalog.merge(
                Arrays.asList(oldBoard, second), Arrays.asList(refreshed)
        );

        assertEquals(2, merged.size());
        assertEquals("新名称", merged.get(0).title);
        assertEquals("nga:-7", merged.get(1).key());
    }

    @Test
    public void filtersBySiteAndCaseInsensitiveNameOrBoardIdWithoutChangingTheCatalog() {
        List<BoardDefinition> boards = Arrays.asList(
                board("v2ex", "python", "Python"),
                board("s1", "157", "卓明谷"),
                board("nga", "-7", "大漩涡")
        );

        assertEquals(1, BoardCatalog.filter(boards, "v2ex", " PYTHON ").size());
        assertEquals(0, BoardCatalog.filter(boards, "s1", "python").size());
        assertEquals("s1:157", BoardCatalog.filter(boards, "", "157").get(0).key());
        assertEquals(3, BoardCatalog.filter(boards, "", "").size());
        assertEquals(3, boards.size());
    }

    private static BoardDefinition board(String site, String id, String title) {
        return new BoardDefinition(site, id, title, "https://example.test/" + id, "", site + " " + title);
    }
}
