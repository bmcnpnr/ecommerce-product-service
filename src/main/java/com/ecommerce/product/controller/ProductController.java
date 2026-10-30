package com.ecommerce.product.controller;

import com.ecommerce.product.dto.*;
import com.ecommerce.product.service.ProductService;
import com.ecommerce.product.exception.InvalidRequestException;
import com.ecommerce.product.exception.ProductNotFoundException;
import com.ecommerce.product.security.AdminOnly;
import com.ecommerce.product.security.AuthorizationInterceptor;
import jakarta.servlet.http.HttpServletRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/products")
@RequiredArgsConstructor
@Tag(name = "Products", description = "Product catalog management")
public class ProductController {

    /** Product names are at most 200 characters, so a longer search text can never match and only costs a scan. */
    private static final int MAX_QUERY_LENGTH = 200;

    private final ProductService productService;

    @PostMapping
    @AdminOnly
    @Operation(summary = "Create a new product (ADMIN)")
    public ResponseEntity<ProductDTO> createProduct(@Valid @RequestBody CreateProductRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(productService.createProduct(request));
    }

    @GetMapping
    @Operation(summary = "List all active products")
    public ResponseEntity<Page<ProductDTO>> getAllProducts(@PageableDefault(size = 20, sort = "id") Pageable pageable) {
        PageLimits.validate(pageable);
        return ResponseEntity.ok(productService.getAllProducts(pageable));
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get product by ID")
    public ResponseEntity<ProductDTO> getProductById(@PathVariable Long id, HttpServletRequest http) {
        return ResponseEntity.ok(visibleTo(productService.getProductById(id), http));
    }

    @GetMapping("/sku/{sku}")
    @Operation(summary = "Get product by SKU")
    public ResponseEntity<ProductDTO> getProductBySku(@PathVariable String sku, HttpServletRequest http) {
        return ResponseEntity.ok(visibleTo(productService.getProductBySku(sku), http));
    }

    @GetMapping("/search")
    @Operation(summary = "Search products by name")
    public ResponseEntity<Page<ProductDTO>> searchProducts(
            @RequestParam String q,
            @PageableDefault(size = 20, sort = "id") Pageable pageable) {
        PageLimits.validate(pageable);
        if (q.indexOf('\0') >= 0 || q.length() > MAX_QUERY_LENGTH) {
            throw new InvalidRequestException("Invalid value for parameter 'q'");
        }
        return ResponseEntity.ok(productService.searchProducts(q, pageable));
    }

    @GetMapping("/category/{categoryId}")
    @Operation(summary = "Get products by category (first page of at most 100; use page and size for more)")
    public ResponseEntity<List<ProductDTO>> getProductsByCategory(
            @PathVariable Long categoryId,
            @PageableDefault(size = PageLimits.MAX_SIZE, sort = "id") Pageable pageable) {
        PageLimits.validate(pageable);
        return ResponseEntity.ok(productService.getProductsByCategory(categoryId, pageable));
    }

    @PutMapping("/{id}")
    @AdminOnly
    @Operation(summary = "Update a product (ADMIN)")
    public ResponseEntity<ProductDTO> updateProduct(@PathVariable Long id, @Valid @RequestBody UpdateProductRequest request) {
        return ResponseEntity.ok(productService.updateProduct(id, request));
    }

    @PatchMapping("/{id}/stock")
    @AdminOnly
    @Operation(summary = "Update product stock (ADMIN)")
    public ResponseEntity<ProductDTO> updateStock(@PathVariable Long id, @Valid @RequestBody StockUpdateRequest request) {
        return ResponseEntity.ok(productService.updateStock(id, request.getDelta()));
    }

    @DeleteMapping("/{id}")
    @AdminOnly
    @Operation(summary = "Delete product (soft delete, ADMIN)")
    public ResponseEntity<Void> deleteProduct(@PathVariable Long id) {
        productService.deleteProduct(id);
        return ResponseEntity.noContent().build();
    }

    /** A soft-deleted (INACTIVE) product is 404 for everyone but an administrator (S-44). */
    private static ProductDTO visibleTo(ProductDTO product, HttpServletRequest http) {
        if (product != null && "INACTIVE".equals(product.getStatus()) && !AuthorizationInterceptor.isAdmin(http)) {
            throw new ProductNotFoundException("Product not found");
        }
        return product;
    }
}
