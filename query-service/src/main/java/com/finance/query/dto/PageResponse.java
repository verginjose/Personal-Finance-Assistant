package com.finance.query.dto;

import java.util.List;
import org.springframework.data.domain.Page;

/** Stable HTTP pagination contract, independent of PageImpl and Redis serialization. */
public record PageResponse<T>(List<T> content, int number, int size, long totalElements,
                              int totalPages, boolean first, boolean last,
                              int numberOfElements, boolean empty) {
    public static <T> PageResponse<T> from(Page<T> page) {
        return new PageResponse<>(page.getContent(), page.getNumber(), page.getSize(),
                page.getTotalElements(), page.getTotalPages(), page.isFirst(), page.isLast(),
                page.getNumberOfElements(), page.isEmpty());
    }
}
