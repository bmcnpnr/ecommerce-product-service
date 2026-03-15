package com.ecommerce.product.dto;

import jakarta.validation.constraints.NotNull;
import lombok.*;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class StockUpdateRequest {
    @NotNull(message = "Delta is required")
    private Integer delta; // positive = add stock, negative = reduce stock
}
