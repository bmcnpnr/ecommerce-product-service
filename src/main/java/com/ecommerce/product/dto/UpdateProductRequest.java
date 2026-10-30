package com.ecommerce.product.dto;

import jakarta.validation.constraints.*;
import lombok.*;
import java.math.BigDecimal;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UpdateProductRequest {
    @Size(max = 200)
    @NoNul
    private String name;

    // TEXT column, but every list response carries it: unbounded, one row would make a whole page huge.
    @Size(max = 5000)
    @NoNul
    private String description;

    @DecimalMin(value = "0", inclusive = false, message = "Price must be greater than 0")
    @Digits(integer = 15, fraction = 2, message = "Price must have at most 15 integer digits and 2 decimals")
    private BigDecimal price;

    @Size(max = 100)
    @NoNul
    private String brand;

    private Long categoryId;

    @Size(max = 30)
    @NoNul
    private String status;
}
