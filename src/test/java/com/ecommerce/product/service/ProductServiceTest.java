package com.ecommerce.product.service;

import com.ecommerce.product.dto.*;
import com.ecommerce.product.exception.*;
import com.ecommerce.product.model.*;
import com.ecommerce.product.repository.CategoryRepository;
import com.ecommerce.product.repository.ProductRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ProductServiceTest {

    @Mock
    private ProductRepository productRepository;
    @Mock
    private CategoryRepository categoryRepository;

    @InjectMocks
    private ProductService productService;

    private Product testProduct;

    @BeforeEach
    void setUp() {
        testProduct = Product.builder()
                .id(1L).sku("SKU-001").name("Test Product")
                .price(new BigDecimal("29.99")).stockQuantity(100)
                .status(ProductStatus.ACTIVE)
                .build();
    }

    @Test
    void createProduct_success() {
        CreateProductRequest request = CreateProductRequest.builder()
                .sku("NEW-SKU").name("New Product").price(new BigDecimal("9.99")).stockQuantity(50).build();

        when(productRepository.existsBySku("NEW-SKU")).thenReturn(false);
        when(productRepository.save(any())).thenAnswer(inv -> {
            Product p = inv.getArgument(0);
            p.setId(2L);
            return p;
        });

        ProductDTO result = productService.createProduct(request);

        assertThat(result.getSku()).isEqualTo("NEW-SKU");
        assertThat(result.getName()).isEqualTo("New Product");
    }

    @Test
    void createProduct_duplicateSku_throwsException() {
        CreateProductRequest request = CreateProductRequest.builder()
                .sku("SKU-001").name("Dup").price(BigDecimal.ONE).stockQuantity(1).build();
        when(productRepository.existsBySku("SKU-001")).thenReturn(true);

        assertThatThrownBy(() -> productService.createProduct(request))
                .isInstanceOf(ProductAlreadyExistsException.class);
    }

    @Test
    void getProductById_found() {
        when(productRepository.findById(1L)).thenReturn(Optional.of(testProduct));

        ProductDTO result = productService.getProductById(1L);
        assertThat(result.getSku()).isEqualTo("SKU-001");
    }

    @Test
    void getProductById_notFound_throwsException() {
        when(productRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> productService.getProductById(99L))
                .isInstanceOf(ProductNotFoundException.class);
    }

    @Test
    void updateStock_success() {
        when(productRepository.findById(1L)).thenReturn(Optional.of(testProduct));
        when(productRepository.save(any())).thenReturn(testProduct);

        ProductDTO result = productService.updateStock(1L, -10);
        assertThat(testProduct.getStockQuantity()).isEqualTo(90);
    }

    @Test
    void updateStock_insufficientStock_throwsException() {
        when(productRepository.findById(1L)).thenReturn(Optional.of(testProduct));

        assertThatThrownBy(() -> productService.updateStock(1L, -200))
                .isInstanceOf(InsufficientStockException.class)
                .hasMessageContaining("Insufficient stock");
    }

    @Test
    void updateStock_toZero_setsOutOfStock() {
        when(productRepository.findById(1L)).thenReturn(Optional.of(testProduct));
        when(productRepository.save(any())).thenReturn(testProduct);

        productService.updateStock(1L, -100);
        assertThat(testProduct.getStatus()).isEqualTo(ProductStatus.OUT_OF_STOCK);
    }

    @Test
    void deleteProduct_softDelete() {
        when(productRepository.findById(1L)).thenReturn(Optional.of(testProduct));
        when(productRepository.save(any())).thenReturn(testProduct);

        productService.deleteProduct(1L);
        assertThat(testProduct.getStatus()).isEqualTo(ProductStatus.INACTIVE);
    }
}
