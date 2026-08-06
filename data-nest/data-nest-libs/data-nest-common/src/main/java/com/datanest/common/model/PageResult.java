package com.datanest.common.model;

import java.util.List;

/**
 * Paginated result wrapper.
 */
public record PageResult<T>(
        List<T> records,
        long total,
        long page,
        long pageSize
) {

    public static <T> PageResult<T> of(List<T> records, long total, long page, long pageSize) {
        return new PageResult<>(records, total, page, pageSize);
    }
}
