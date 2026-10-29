package com.ecommerce.product.controller;

import com.ecommerce.product.exception.InvalidRequestException;
import org.springframework.data.domain.Pageable;

/** Page size is capped at 100 and the resulting offset must fit an int; anything else is a client error. */
final class PageLimits {

    static final int MAX_SIZE = 100;

    private static final java.util.Set<String> SORTABLE = java.util.Set.of(
            "id", "sku", "name", "price", "stockQuantity", "brand", "categoryId", "status", "createdAt", "updatedAt");

    private PageLimits() {
    }

    static void validate(Pageable pageable) {
        if (pageable.isUnpaged()) {
            return;
        }
        if (pageable.getPageSize() > MAX_SIZE) {
            throw new InvalidRequestException("Page size must be between 1 and " + MAX_SIZE);
        }
        for (org.springframework.data.domain.Sort.Order order : pageable.getSort()) {
            if (!SORTABLE.contains(order.getProperty())) {
                throw new InvalidRequestException("Invalid sort property");
            }
        }
        if ((long) pageable.getPageNumber() * pageable.getPageSize() > Integer.MAX_VALUE) {
            throw new InvalidRequestException("Page out of range");
        }
    }
}
