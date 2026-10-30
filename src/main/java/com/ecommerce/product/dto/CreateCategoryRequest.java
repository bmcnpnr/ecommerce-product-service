package com.ecommerce.product.dto;

import jakarta.validation.constraints.*;
import lombok.*;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CreateCategoryRequest {
    @NotBlank(message = "Category name is required")
    @Size(max = 100)
    @NoNul
    private String name;

    // TEXT column, but every list response carries it: unbounded, one row would make a whole page huge.
    @Size(max = 5000)
    @NoNul
    private String description;

    private Long parentCategoryId;
}
