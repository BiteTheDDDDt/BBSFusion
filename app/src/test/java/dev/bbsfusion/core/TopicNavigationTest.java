package dev.bbsfusion.core;

import org.junit.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;

public final class TopicNavigationTest {
    @Test public void locatesARealFloorWhenItsNumberIsNotAnOffset() throws Exception {
        TopicDetail first = page(1, 3, 2, 1, 2);
        TopicDetail second = page(2, 3, 0, 8, 9);
        TopicDetail last = page(3, 3, 0, 11, 12);
        List<Integer> requests = new ArrayList<>();

        TopicDetail result = TopicNavigation.findFloor(first, 8, number -> {
            requests.add(number);
            return number == 2 ? second : last;
        });

        assertSame(second, result);
        assertEquals(Arrays.asList(3, 2), requests);
    }

    @Test public void rejectsDeletedFloorInsideAVisibleRange() {
        TopicDetail current = page(2, 3, 0, 40, 42);
        assertThrows(IOException.class, () -> TopicNavigation.findFloor(current, 41, number -> {
            throw new AssertionError("The current page already establishes the gap");
        }));
    }

    @Test public void acceptsTheRealLastPageWhenTheSiteClampsAStalePageCount() throws Exception {
        TopicDetail current = page(2, 10, 20, 21, 22);
        TopicDetail last = page(2, 2, 20, 21, 22, 23);

        assertSame(last, TopicNavigation.findLastPage(current, number -> {
            assertEquals(10, number);
            return last;
        }));
    }

    private static TopicDetail page(int number, int total, int size, int... floors) {
        List<Post> posts = new ArrayList<>();
        for (int floor : floors) {
            posts.add(new Post("Author", "", "", "", "Reply", List.of(), List.of(), floor));
        }
        return new TopicDetail("Topic", "https://example.test/topic", posts, number,
                number < total, total, size);
    }
}
