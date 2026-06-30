package com.example.sso.scim;

import org.springframework.data.domain.AbstractPageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

/**
 * A {@link Pageable} addressed by an arbitrary 0-based offset (not a page index), so SCIM's
 * 1-based {@code startIndex} maps exactly to a SQL OFFSET regardless of page alignment.
 */
public final class OffsetPageable extends AbstractPageRequest {

    private final long offset;

    private OffsetPageable(long offset, int size) {
        super((int) (offset / size), size);
        this.offset = offset;
    }

    /** @param startIndex SCIM 1-based start index; @param size page size (≥1). */
    public static OffsetPageable fromStartIndex(long startIndex, int size) {
        long zeroBased = Math.max(0, startIndex - 1);
        return new OffsetPageable(zeroBased, Math.max(1, size));
    }

    @Override
    public long getOffset() {
        return offset;
    }

    @Override
    public Sort getSort() {
        return Sort.unsorted();
    }

    @Override
    public Pageable next() {
        return new OffsetPageable(offset + getPageSize(), getPageSize());
    }

    @Override
    public Pageable previous() {
        return new OffsetPageable(Math.max(0, offset - getPageSize()), getPageSize());
    }

    @Override
    public Pageable first() {
        return new OffsetPageable(0, getPageSize());
    }

    @Override
    public Pageable withPage(int pageNumber) {
        return new OffsetPageable((long) pageNumber * getPageSize(), getPageSize());
    }
}
