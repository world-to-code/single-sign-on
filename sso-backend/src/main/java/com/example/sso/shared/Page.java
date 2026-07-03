package com.example.sso.shared;

import java.util.List;
import java.util.function.Function;

/**
 * A single slice of a larger result set for admin list views: the page's {@code items} plus the total
 * count so the UI can render "page X of Y". {@code page} is 0-based; {@code size} is the page size that
 * was actually applied (clamped).
 */
public record Page<T>(long total, int page, int size, List<T> items) {

    /** Upper bound on a page size, so a caller can't request an unbounded slice. */
    public static final int MAX_SIZE = 100;
    private static final int DEFAULT_SIZE = 20;

    /**
     * Slices an already-materialized list into the requested page. Used where the full result is small
     * enough to hold in memory (an unpaged repository read or an in-memory scope filter), returning one
     * page of it with the true total. Lists with a lazy per-row projection page in the DB instead.
     */
    public static <T> Page<T> of(List<T> all, int page, int size) {
        int safeSize = size <= 0 ? DEFAULT_SIZE : Math.min(size, MAX_SIZE);
        int safePage = Math.max(page, 0);
        int from = (int) Math.min((long) safePage * safeSize, all.size());
        int to = Math.min(from + safeSize, all.size());
        return new Page<>(all.size(), safePage, safeSize, List.copyOf(all.subList(from, to)));
    }

    /** Clamps a requested page size to {@code [1, MAX_SIZE]} for DB-level paging (a {@code PageRequest} size). */
    public static int clampSize(int size) {
        return size <= 0 ? DEFAULT_SIZE : Math.min(size, MAX_SIZE);
    }

    /** Clamps a requested page index to a non-negative value. */
    public static int clampPage(int page) {
        return Math.max(page, 0);
    }

    /** Projects each item to a view type, preserving the paging metadata — for a DB-paged entity page. */
    public <R> Page<R> map(Function<? super T, R> mapper) {
        return new Page<>(total, page, size, items.stream().map(mapper).toList());
    }
}
