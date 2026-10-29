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

    @NoNul
    private String description;

    private Long parentCategoryId;
}
