package com.ecommerce.product.controller;

import com.ecommerce.product.dto.CategoryDTO;
import com.ecommerce.product.dto.CreateCategoryRequest;
import com.ecommerce.product.exception.CategoryNotFoundException;
import com.ecommerce.product.exception.ProductAlreadyExistsException;
import com.ecommerce.product.service.ProductService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.hamcrest.Matchers.hasSize;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = CategoryController.class)
class CategoryControllerTest {

    @Autowired
    private MockMvc mockMvc;

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @MockitoBean
    private ProductService productService;

    @Test
    void createCategory_returns201() throws Exception {
        CreateCategoryRequest request = CreateCategoryRequest.builder()
                .name("Mice").description("Pointing devices").parentCategoryId(10L).build();
        when(productService.createCategory(any())).thenReturn(
                CategoryDTO.builder().id(11L).name("Mice").description("Pointing devices").parentCategoryId(10L).build());

        mockMvc.perform(post("/api/v1/categories").header("X-User-Role", "ADMIN")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(11))
                .andExpect(jsonPath("$.parentCategoryId").value(10));

        verify(productService).createCategory(argThat(r ->
                r.getName().equals("Mice") && r.getParentCategoryId().equals(10L)));
    }

    @Test
    void createCategory_blankName_returns400WithFieldError() throws Exception {
        mockMvc.perform(post("/api/v1/categories").header("X-User-Role", "ADMIN")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"  \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Validation failed"))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("name"))
                .andExpect(jsonPath("$.fieldErrors[0].message").value("Category name is required"));

        verify(productService, never()).createCategory(any());
    }

    @Test
    void createCategory_nameTooLong_returns400() throws Exception {
        mockMvc.perform(post("/api/v1/categories").header("X-User-Role", "ADMIN")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + "x".repeat(101) + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[0].field").value("name"));
    }

    @Test
    void createCategory_duplicate_returns409() throws Exception {
        when(productService.createCategory(any())).thenThrow(new ProductAlreadyExistsException("Category already exists: Mice"));

        mockMvc.perform(post("/api/v1/categories").header("X-User-Role", "ADMIN")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Mice\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.message").value("Category already exists: Mice"));
    }

    @Test
    void getAllCategories_returns200() throws Exception {
        when(productService.getAllCategories()).thenReturn(List.of(
                CategoryDTO.builder().id(10L).name("Electronics").build(),
                CategoryDTO.builder().id(11L).name("Mice").parentCategoryId(10L).build()));

        mockMvc.perform(get("/api/v1/categories"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[1].parentCategoryId").value(10));
    }

    @Test
    void getAllCategories_empty_returnsEmptyArray() throws Exception {
        when(productService.getAllCategories()).thenReturn(List.of());

        mockMvc.perform(get("/api/v1/categories"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));
    }

    @Test
    void getCategoryById_found_returns200() throws Exception {
        when(productService.getCategoryById(10L)).thenReturn(CategoryDTO.builder().id(10L).name("Electronics").build());

        mockMvc.perform(get("/api/v1/categories/10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Electronics"));
    }

    @Test
    void getCategoryById_notFound_returns404() throws Exception {
        when(productService.getCategoryById(99L)).thenThrow(new CategoryNotFoundException("Category not found: 99"));

        mockMvc.perform(get("/api/v1/categories/99"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("Not Found"))
                .andExpect(jsonPath("$.message").value("Category not found: 99"))
                .andExpect(jsonPath("$.path").value("/api/v1/categories/99"));
    }

    @Test
    void getCategoryById_nonNumericId_returns400() throws Exception {
        mockMvc.perform(get("/api/v1/categories/abc"))
                .andExpect(status().isBadRequest());
    }
}
