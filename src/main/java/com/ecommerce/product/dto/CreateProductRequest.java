package com.ecommerce.product.dto;

import jakarta.validation.constraints.*;
import lombok.*;
import java.math.BigDecimal;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CreateProductRequest {
    @NotBlank(message = "SKU is required")
    @Size(max = 100)
    @NoNul
    private String sku;

    @NotBlank(message = "Name is required")
    @Size(max = 200)
    @NoNul
    private String name;

    // TEXT column, but every list response carries it: unbounded, one row would make a whole page huge.
    @Size(max = 5000)
    @NoNul
    private String description;

    @NotNull(message = "Price is required")
    @DecimalMin(value = "0", inclusive = false, message = "Price must be greater than 0")
    @Digits(integer = 15, fraction = 2, message = "Price must have at most 15 integer digits and 2 decimals")
    private BigDecimal price;

    @NotNull(message = "Stock quantity is required")
    @Min(value = 0, message = "Stock quantity cannot be negative")
    private Integer stockQuantity;

    @Size(max = 100)
    @NoNul
    private String brand;

    private Long categoryId;
}
