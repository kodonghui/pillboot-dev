package org.semiproject.pillbadeumserver.global.response;

import java.util.List;
import org.springframework.data.domain.Page;

/** Shared shape for future paginated endpoints. HTTP page numbers start at 1. */
public record PageResponse<T>(List<T> content, long totalElements, int totalPages,
        int size, int number, boolean first, boolean last) {
    public static <T> PageResponse<T> from(Page<T> page) {
        return new PageResponse<>(List.copyOf(page.getContent()), page.getTotalElements(), page.getTotalPages(),
                page.getSize(), page.getNumber() + 1, page.isFirst(), page.isLast());
    }
}
