package com.ecommerce.product.controller;

import com.ecommerce.product.dto.*;
import com.ecommerce.product.exception.InsufficientStockException;
import com.ecommerce.product.exception.ProductNotFoundException;
import com.ecommerce.product.service.ProductService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
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

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

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

        mockMvc.perform(post("/api/v1/products").header("X-User-Role", "ADMIN")
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

        mockMvc.perform(patch("/api/v1/products/1/stock").header("X-User-Role", "ADMIN")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void getAllProducts_defaultsToPageSize20() throws Exception {
        when(productService.getAllProducts(any())).thenReturn(new PageImpl<>(List.of(
                ProductDTO.builder().id(1L).sku("A").status("ACTIVE").build())));

        mockMvc.perform(get("/api/v1/products"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].sku").value("A"));

        verify(productService).getAllProducts(argThat((Pageable p) -> p.getPageSize() == 20 && p.getPageNumber() == 0));
    }

    @Test
    void getAllProducts_honoursPageSizeAndSortParams() throws Exception {
        when(productService.getAllProducts(any())).thenReturn(new PageImpl<>(List.of()));

        mockMvc.perform(get("/api/v1/products?page=3&size=5&sort=price,desc"))
                .andExpect(status().isOk());

        verify(productService).getAllProducts(argThat((Pageable p) -> p.getPageNumber() == 3 && p.getPageSize() == 5
                && p.getSort().getOrderFor("price") != null && p.getSort().getOrderFor("price").isDescending()));
    }

    @Test
    void getProductBySku_returns200() throws Exception {
        when(productService.getProductBySku("SKU-9")).thenReturn(ProductDTO.builder().id(9L).sku("SKU-9").build());

        mockMvc.perform(get("/api/v1/products/sku/SKU-9"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(9));
    }

    @Test
    void searchProducts_passesQueryAndPageable() throws Exception {
        when(productService.searchProducts(eq("mouse"), any())).thenReturn(new PageImpl<>(List.of(
                ProductDTO.builder().id(1L).name("Wireless Mouse").build())));

        mockMvc.perform(get("/api/v1/products/search").param("q", "mouse").param("size", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].name").value("Wireless Mouse"));

        verify(productService).searchProducts(eq("mouse"), argThat((Pageable p) -> p.getPageSize() == 1));
    }

    @Test
    void getProductsByCategory_returnsList() throws Exception {
        when(productService.getProductsByCategory(eq(10L), any(Pageable.class))).thenReturn(List.of(
                ProductDTO.builder().id(1L).categoryId(10L).build(),
                ProductDTO.builder().id(2L).categoryId(10L).build()));

        mockMvc.perform(get("/api/v1/products/category/10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));
    }

    @Test
    void theLists_areSortedById_unlessTheClientAsksForAnotherOrder_soPagesAreStable() throws Exception {
        when(productService.getAllProducts(any(Pageable.class))).thenReturn(new PageImpl<>(List.of()));
        when(productService.searchProducts(eq("mouse"), any(Pageable.class))).thenReturn(new PageImpl<>(List.of()));

        mockMvc.perform(get("/api/v1/products")).andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/products/search").param("q", "mouse")).andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/products").param("sort", "price,desc")).andExpect(status().isOk());

        verify(productService, times(2)).getAllProducts(argThat((Pageable p) ->
                p.getPageSize() == 20 && (p.getSort().equals(org.springframework.data.domain.Sort.by("id"))
                        || p.getSort().equals(org.springframework.data.domain.Sort.by(org.springframework.data.domain.Sort.Direction.DESC, "price")))));
        verify(productService).searchProducts(eq("mouse"), argThat((Pageable p) -> p.getSort().equals(org.springframework.data.domain.Sort.by("id"))));
    }

    @Test
    void getProductsByCategory_isOnePageOfAtMost100_sortedById_byDefault() throws Exception {
        when(productService.getProductsByCategory(eq(10L), any(Pageable.class))).thenReturn(List.of());

        mockMvc.perform(get("/api/v1/products/category/10")).andExpect(status().isOk());

        verify(productService).getProductsByCategory(eq(10L), argThat((Pageable p) ->
                p.getPageNumber() == 0 && p.getPageSize() == 100 && p.getSort().equals(org.springframework.data.domain.Sort.by("id"))));
    }

    @Test
    void getProductsByCategory_pageAndSizeAreHonoured_anOversizedPageIsA400() throws Exception {
        when(productService.getProductsByCategory(eq(10L), any(Pageable.class))).thenReturn(List.of());

        mockMvc.perform(get("/api/v1/products/category/10").param("page", "2").param("size", "50")).andExpect(status().isOk());
        verify(productService).getProductsByCategory(eq(10L), argThat((Pageable p) -> p.getPageNumber() == 2 && p.getPageSize() == 50));

        mockMvc.perform(get("/api/v1/products/category/10").param("size", "101")).andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/v1/products/category/10").param("size", "2147483647")).andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/v1/products/category/10").param("sort", "password")).andExpect(status().isBadRequest());
        verifyNoMoreInteractions(productService);
    }

    @Test
    void search_textLongerThanAnyProductName_isA400_andNeverReachesTheDatabase() throws Exception {
        mockMvc.perform(get("/api/v1/products/search").param("q", "a".repeat(201)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Invalid value for parameter 'q'"));
        verifyNoInteractions(productService);

        when(productService.searchProducts(eq("a".repeat(200)), any(Pageable.class))).thenReturn(new PageImpl<>(List.of()));
        mockMvc.perform(get("/api/v1/products/search").param("q", "a".repeat(200))).andExpect(status().isOk());
    }

    @Test
    void aRequestBodyOver1MB_isRefusedBeforeAnythingParsesIt() throws Exception {
        byte[] huge = ("{\"sku\":\"A\",\"name\":\"" + "x".repeat(2 * 1024 * 1024) + "\"}").getBytes(java.nio.charset.StandardCharsets.UTF_8);

        mockMvc.perform(post("/api/v1/products").header("X-User-Role", "ADMIN").contentType(MediaType.APPLICATION_JSON).content(huge))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.message").value("Request body too large"));

        verifyNoInteractions(productService);
    }

    @Test
    void updateProduct_returns200() throws Exception {
        UpdateProductRequest request = UpdateProductRequest.builder().name("Renamed").price(new BigDecimal("5.00")).build();
        when(productService.updateProduct(eq(1L), any())).thenReturn(
                ProductDTO.builder().id(1L).name("Renamed").price(new BigDecimal("5.00")).build());

        mockMvc.perform(put("/api/v1/products/1").header("X-User-Role", "ADMIN")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Renamed"));
    }

    @Test
    void updateProduct_notFound_returns404() throws Exception {
        when(productService.updateProduct(eq(9L), any())).thenThrow(new ProductNotFoundException("Product not found with id: 9"));

        mockMvc.perform(put("/api/v1/products/9").header("X-User-Role", "ADMIN").contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"x\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void updateStock_returns200() throws Exception {
        when(productService.updateStock(1L, 5)).thenReturn(ProductDTO.builder().id(1L).stockQuantity(15).status("ACTIVE").build());

        mockMvc.perform(patch("/api/v1/products/1/stock").header("X-User-Role", "ADMIN")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new StockUpdateRequest(5))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stockQuantity").value(15));
    }

    @Test
    void deleteProduct_returns204() throws Exception {
        mockMvc.perform(delete("/api/v1/products/1").header("X-User-Role", "ADMIN"))
                .andExpect(status().isNoContent());

        verify(productService).deleteProduct(1L);
    }

    @Test
    void deleteProduct_notFound_returns404() throws Exception {
        doThrow(new ProductNotFoundException("nope")).when(productService).deleteProduct(9L);

        mockMvc.perform(delete("/api/v1/products/9").header("X-User-Role", "ADMIN"))
                .andExpect(status().isNotFound());
    }

    @Test
    void createProduct_invalidInput_returns400() throws Exception {
        CreateProductRequest request = CreateProductRequest.builder()
                .sku("").name("").price(new BigDecimal("-1")).stockQuantity(-5).build();

        mockMvc.perform(post("/api/v1/products").header("X-User-Role", "ADMIN")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());
    }
}
