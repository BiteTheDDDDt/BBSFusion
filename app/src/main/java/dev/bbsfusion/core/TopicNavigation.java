package dev.bbsfusion.core;

import java.io.IOException;

/** Resolves a single user-initiated jump using the site's pagination and floor numbers. */
public final class TopicNavigation {
    private static final int MAX_FLOOR_PROBES = 32;
    private static final int MAX_END_PROBES = 6;

    private TopicNavigation() {}

    public interface PageFetcher {
        TopicDetail fetch(int page) throws IOException;
    }

    public static TopicDetail findFloor(TopicDetail current, int floor, PageFetcher fetcher)
            throws IOException {
        if (floor <= 0) { throw new IOException("请输入有效的楼层号。"); }
        TopicDetail estimated = null;
        if (current.pageSize > 0) {
            int page = (floor - 1) / current.pageSize + 1;
            if (current.lastPage > 0) { page = Math.min(page, current.lastPage); }
            estimated = page == current.pageNumber ? current : fetcher.fetch(page);
            if (containsFloor(estimated, floor)) { return estimated; }
        }
        if (current.lastPage == 0) {
            if (estimated != null) { throw floorNotFound(floor); }
            if (!current.hasMore) { throw floorNotFound(floor); }
            throw new IOException("原站没有提供足够的分页信息，可在原站查找这一楼。");
        }

        // Some sites leave holes in their floor numbers after posts are removed.
        int low = 1;
        int high = current.lastPage;
        int firstCurrent = current.posts.stream().mapToInt(post -> post.floorNumber)
                .filter(number -> number > 0).min().orElse(0);
        int lastCurrent = current.posts.stream().mapToInt(post -> post.floorNumber).max().orElse(0);
        if (lastCurrent > 0 && floor > lastCurrent && current.pageNumber < Integer.MAX_VALUE) {
            low = current.pageNumber + 1;
        } else if (firstCurrent > 0 && floor < firstCurrent) {
            high = current.pageNumber - 1;
        }
        for (int i = 0; i < MAX_FLOOR_PROBES && low <= high; i++) {
            int page = low + (high - low) / 2;
            TopicDetail result = page == current.pageNumber ? current
                    : estimated != null && estimated.pageNumber == page ? estimated : fetcher.fetch(page);
            int first = Integer.MAX_VALUE;
            int last = 0;
            for (Post post : result.posts) {
                int number = post.floorNumber;
                if (number == floor) { return result; }
                if (number > 0) {
                    first = Math.min(first, number);
                    last = Math.max(last, number);
                }
            }
            if (result.pageNumber != page || last == 0) { break; }
            if (floor < first) {
                high = page - 1;
            } else if (floor > last && page < Integer.MAX_VALUE) {
                low = page + 1;
            } else {
                break;
            }
        }
        throw floorNotFound(floor);
    }

    public static TopicDetail findLastPage(TopicDetail current, PageFetcher fetcher)
            throws IOException {
        TopicDetail detail = current;
        for (int i = 0; i < MAX_END_PROBES && detail.hasMore; i++) {
            int page = detail.lastPage > detail.pageNumber ? detail.lastPage : detail.pageNumber + 1;
            if (page <= detail.pageNumber) { break; }
            TopicDetail next = fetcher.fetch(page);
            if (next.posts.isEmpty() || next.pageNumber < detail.pageNumber) { break; }
            if (next.pageNumber == detail.pageNumber && next.hasMore) { break; }
            detail = next;
        }
        if (!detail.hasMore && !detail.posts.isEmpty()) { return detail; }
        throw new IOException("无法确认末页，可在原站查看最后一楼。");
    }

    private static boolean containsFloor(TopicDetail detail, int floor) {
        for (Post post : detail.posts) {
            if (post.floorNumber == floor) { return true; }
        }
        return false;
    }

    private static IOException floorNotFound(int floor) {
        return new IOException("未找到第 " + floor + " 楼。");
    }
}
