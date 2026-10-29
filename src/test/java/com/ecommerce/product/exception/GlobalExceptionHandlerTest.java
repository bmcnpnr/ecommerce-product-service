package com.ecommerce.product.exception;

import com.ecommerce.product.controller.ProductController;
import com.ecommerce.product.service.ProductService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Every branch of {@link GlobalExceptionHandler}, driven through the real MVC stack
 * (incl. {@code CorrelationIdFilter}, which @WebMvcTest picks up as a Filter bean).
 */
@WebMvcTest(controllers = ProductController.class)
class GlobalExceptionHandlerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ProductService productService;

    @Test
    void productNotFound_is404_withFullErrorBody() throws Exception {
        when(productService.getProductById(7L)).thenThrow(new ProductNotFoundException("Product not found with id: 7"));

        mockMvc.perform(get("/api/v1/products/7").header("X-Correlation-Id", "corr-404"))
                .andExpect(status().isNotFound())
                .andExpect(header().string("X-Correlation-Id", "corr-404"))
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.error").value("Not Found"))
                .andExpect(jsonPath("$.message").value("Product not found with id: 7"))
                .andExpect(jsonPath("$.path").value("/api/v1/products/7"))
                .andExpect(jsonPath("$.correlationId").value("corr-404"))
                .andExpect(jsonPath("$.timestamp").exists())
                .andExpect(jsonPath("$.fieldErrors").doesNotExist());
    }

    @Test
    void categoryNotFound_is404() throws Exception {
        when(productService.getProductsByCategory(5L)).thenThrow(new CategoryNotFoundException("Category not found: 5"));

        mockMvc.perform(get("/api/v1/products/category/5"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("Not Found"))
                .andExpect(jsonPath("$.message").value("Category not found: 5"));
    }

    @Test
    void duplicateSku_is409() throws Exception {
        when(productService.createProduct(any())).thenThrow(new ProductAlreadyExistsException("Product with SKU already exists: SKU-1"));

        mockMvc.perform(post("/api/v1/products").header("X-User-Role", "ADMIN").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sku\":\"SKU-1\",\"name\":\"N\",\"price\":1.00,\"stockQuantity\":1}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.error").value("Conflict"))
                .andExpect(jsonPath("$.message").value("Product with SKU already exists: SKU-1"));
    }

    @Test
    void insufficientStock_is400_withMessage() throws Exception {
        when(productService.updateStock(eq(1L), eq(-5)))
                .thenThrow(new InsufficientStockException("Insufficient stock for product SKU-1. Available: 2, Requested: 5"));

        mockMvc.perform(patch("/api/v1/products/1/stock").header("X-User-Role", "ADMIN").contentType(MediaType.APPLICATION_JSON).content("{\"delta\":-5}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Bad Request"))
                .andExpect(jsonPath("$.message").value("Insufficient stock for product SKU-1. Available: 2, Requested: 5"));
    }

    @Test
    void validation_is400_withOneFieldErrorPerViolation() throws Exception {
        mockMvc.perform(post("/api/v1/products").header("X-User-Role", "ADMIN").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sku\":\"\",\"name\":\"\",\"price\":0,\"stockQuantity\":-1}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Validation failed"))
                .andExpect(jsonPath("$.fieldErrors", hasSize(4)))
                .andExpect(jsonPath("$.fieldErrors[*].field", containsInAnyOrder("sku", "name", "price", "stockQuantity")))
                .andExpect(jsonPath("$.fieldErrors[?(@.field=='price')].message").value("Price must be greater than 0"));
    }

    @Test
    void validation_missingRequiredFields_is400() throws Exception {
        mockMvc.perform(post("/api/v1/products").header("X-User-Role", "ADMIN").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors", hasSize(4)));
    }

    @Test
    void stockUpdate_missingDelta_is400() throws Exception {
        mockMvc.perform(patch("/api/v1/products/1/stock").header("X-User-Role", "ADMIN").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[0].field").value("delta"))
                .andExpect(jsonPath("$.fieldErrors[0].message").value("Delta is required"));
    }

    @Test
    void updateProduct_nonPositivePrice_is400() throws Exception {
        mockMvc.perform(put("/api/v1/products/1").header("X-User-Role", "ADMIN").contentType(MediaType.APPLICATION_JSON).content("{\"price\":0}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[0].field").value("price"));
    }

    @Test
    void malformedJson_is400() throws Exception {
        mockMvc.perform(post("/api/v1/products").header("X-User-Role", "ADMIN").contentType(MediaType.APPLICATION_JSON).content("{not json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400));
    }

    @Test
    void missingRequestParam_is400() throws Exception {
        mockMvc.perform(get("/api/v1/products/search"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("q")));
    }

    @Test
    void typeMismatch_is400() throws Exception {
        mockMvc.perform(get("/api/v1/products/not-a-number"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void methodNotAllowed_is405() throws Exception {
        mockMvc.perform(post("/api/v1/products/1").header("X-User-Role", "ADMIN"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.status").value(405))
                .andExpect(jsonPath("$.error").value("Method Not Allowed"));
    }

    @Test
    void unexpectedException_is500_withoutLeakingDetails() throws Exception {
        when(productService.getProductById(anyLong())).thenThrow(new IllegalStateException("db password is hunter2"));

        mockMvc.perform(get("/api/v1/products/1"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.status").value(500))
                .andExpect(jsonPath("$.message").value("An unexpected error occurred"))
                .andExpect(content().string(not(containsString("hunter2"))));
    }

    @Test
    void correlationIdIsGeneratedWhenAbsent_andEchoedInBody() throws Exception {
        when(productService.getProductById(7L)).thenThrow(new ProductNotFoundException("nope"));

        mockMvc.perform(get("/api/v1/products/7"))
                .andExpect(status().isNotFound())
                .andExpect(header().exists("X-Correlation-Id"))
                .andExpect(result -> org.assertj.core.api.Assertions.assertThat(
                                result.getResponse().getContentAsString())
                        .contains(result.getResponse().getHeader("X-Correlation-Id")));
    }
}
