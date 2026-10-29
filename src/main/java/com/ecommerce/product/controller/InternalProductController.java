package com.ecommerce.product.controller;

import com.ecommerce.product.dto.ProductDTO;
import com.ecommerce.product.dto.StockUpdateRequest;
import com.ecommerce.product.service.ProductService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/** Service-to-service endpoints. The gateway never routes {@code /internal/**}; no identity is required. */
@RestController
@RequestMapping("/internal/v1/products")
@RequiredArgsConstructor
@Tag(name = "Internal", description = "Service-to-service endpoints (not routed by the gateway)")
public class InternalProductController {

    private final ProductService productService;

    @PatchMapping("/{id}/stock")
    @Operation(summary = "Adjust product stock (internal, used by order-service)")
    public ResponseEntity<ProductDTO> updateStock(@PathVariable Long id, @Valid @RequestBody StockUpdateRequest request) {
        return ResponseEntity.ok(productService.updateStock(id, request.getDelta()));
    }
}
