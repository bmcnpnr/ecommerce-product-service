package com.ecommerce.product.controller;

import com.ecommerce.product.dto.*;
import com.ecommerce.product.exception.InsufficientStockException;
import com.ecommerce.product.exception.ProductNotFoundException;
import com.ecommerce.product.service.ProductService;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.List;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers = ProductController.class)
class ProductControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private ProductService productService;

    @Test
    void createProduct_returns201() throws Exception {
        CreateProductRequest request = CreateProductRequest.builder()
                .sku("TEST-001").name("Test Product").price(new BigDecimal("19.99")).stockQuantity(50).build();
        ProductDTO response = ProductDTO.builder()
                .id(1L).sku("TEST-001").name("Test Product").price(new BigDecimal("19.99"))
                .stockQuantity(50).status("ACTIVE").build();

        when(productService.createProduct(any())).thenReturn(response);

        mockMvc.perform(post("/api/v1/products")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.sku").value("TEST-001"))
                .andExpect(jsonPath("$.status").value("ACTIVE"));
    }

    @Test
    void getProductById_found_returns200() throws Exception {
        ProductDTO response = ProductDTO.builder().id(1L).sku("SKU-001").name("Product").status("ACTIVE").build();
        when(productService.getProductById(1L)).thenReturn(response);

        mockMvc.perform(get("/api/v1/products/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(1));
    }

    @Test
    void getProductById_notFound_returns404() throws Exception {
        when(productService.getProductById(99L)).thenThrow(new ProductNotFoundException("Not found"));

        mockMvc.perform(get("/api/v1/products/99"))
                .andExpect(status().isNotFound());
    }

    @Test
    void updateStock_insufficientStock_returns400() throws Exception {
        StockUpdateRequest request = new StockUpdateRequest(-1000);
        when(productService.updateStock(eq(1L), eq(-1000))).thenThrow(new InsufficientStockException("Insufficient stock"));

        mockMvc.perform(patch("/api/v1/products/1/stock")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void createProduct_invalidInput_returns400() throws Exception {
        CreateProductRequest request = CreateProductRequest.builder()
                .sku("").name("").price(new BigDecimal("-1")).stockQuantity(-5).build();

        mockMvc.perform(post("/api/v1/products")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());
    }
}
