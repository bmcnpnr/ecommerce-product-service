package com.ecommerce.product.security;

import com.ecommerce.product.controller.CategoryController;
import com.ecommerce.product.controller.ProductController;
import com.ecommerce.product.dto.CategoryDTO;
import com.ecommerce.product.dto.ProductDTO;
import com.ecommerce.product.service.ProductService;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.List;
import java.util.stream.Stream;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The gateway validates the JWT and forwards {@code X-User-Role}. Every write endpoint
 * (documented "ADMIN") must refuse anyone who is not ADMIN, and must do so before the
 * service layer is reached. Reads stay public.
 */
@Tag("security")
@WebMvcTest(controllers = {ProductController.class, CategoryController.class})
class ProductAuthorizationSecurityTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ProductService productService;

    private record Write(String name, HttpMethod method, String path, String body) {
        @Override
        public String toString() {
            return name;
        }
    }

    private static final List<Write> WRITES = List.of(
            new Write("create product", HttpMethod.POST, "/api/v1/products",
                    "{\"sku\":\"S-1\",\"name\":\"n\",\"price\":1.00,\"stockQuantity\":1}"),
            new Write("update product", HttpMethod.PUT, "/api/v1/products/1", "{\"name\":\"hijacked\"}"),
            new Write("adjust stock", HttpMethod.PATCH, "/api/v1/products/1/stock", "{\"delta\":-1}"),
            new Write("delete product", HttpMethod.DELETE, "/api/v1/products/1", null),
            new Write("create category", HttpMethod.POST, "/api/v1/categories", "{\"name\":\"c\"}"));

    /** Header sets that must NOT be treated as an administrator. A null role means "no header at all". */
    private static final List<String> NON_ADMIN_ROLES = java.util.Arrays.asList(
            "CUSTOMER", null, "", "admin", "Admin", " ADMIN", "ADMIN ", "ROLE_ADMIN", "ADMINISTRATOR", "ADMIN,CUSTOMER");

    static Stream<Arguments> nonAdminCallers() {
        return WRITES.stream().flatMap(w -> NON_ADMIN_ROLES.stream().map(r -> Arguments.of(w, r)));
    }

    static Stream<Arguments> writes() {
        return WRITES.stream().map(Arguments::of);
    }

    private MockHttpServletRequestBuilder build(Write w, String role) {
        MockHttpServletRequestBuilder b = request(w.method(), w.path()).header("X-Username", "mallory").header("X-User-Id", "7");
        if (role != null) b.header("X-User-Role", role);
        if (w.body() != null) b.contentType(MediaType.APPLICATION_JSON).content(w.body());
        return b;
    }

    @ParameterizedTest(name = "{0} as role [{1}] is 403 and never reaches the service")
    @MethodSource("nonAdminCallers")
    void writeEndpoint_nonAdmin_isForbidden(Write w, String role) throws Exception {
        mockMvc.perform(build(w, role)).andExpect(status().isForbidden());
        verifyNoInteractions(productService);
    }

    @ParameterizedTest(name = "{0} as ADMIN is not forbidden")
    @MethodSource("writes")
    void writeEndpoint_admin_isAllowed(Write w) throws Exception {
        when(productService.createProduct(any())).thenReturn(ProductDTO.builder().id(1L).sku("S-1").status("ACTIVE").build());
        when(productService.updateProduct(eq(1L), any())).thenReturn(ProductDTO.builder().id(1L).status("ACTIVE").build());
        when(productService.updateStock(eq(1L), any())).thenReturn(ProductDTO.builder().id(1L).status("ACTIVE").build());
        when(productService.createCategory(any())).thenReturn(CategoryDTO.builder().id(1L).name("c").build());

        int status = mockMvc.perform(build(w, "ADMIN")).andReturn().getResponse().getStatus();

        org.assertj.core.api.Assertions.assertThat(status).isIn(200, 201, 204);
    }

    @Test
    void reads_stayPublic_withoutAnyHeaders() throws Exception {
        when(productService.getAllProducts(any(Pageable.class))).thenReturn(new PageImpl<>(List.of()));
        when(productService.searchProducts(any(), any(Pageable.class))).thenReturn(new PageImpl<>(List.of()));
        when(productService.getProductById(anyLong())).thenReturn(ProductDTO.builder().id(1L).status("ACTIVE").build());
        when(productService.getProductBySku(any())).thenReturn(ProductDTO.builder().id(1L).status("ACTIVE").build());
        when(productService.getProductsByCategory(anyLong(), any(Pageable.class))).thenReturn(List.of());
        when(productService.getAllCategories()).thenReturn(List.of());
        when(productService.getCategoryById(anyLong())).thenReturn(CategoryDTO.builder().id(1L).name("c").build());

        for (String path : List.of("/api/v1/products", "/api/v1/products/1", "/api/v1/products/sku/X",
                "/api/v1/products/search?q=a", "/api/v1/products/category/1", "/api/v1/categories", "/api/v1/categories/1")) {
            mockMvc.perform(request(HttpMethod.GET, path)).andExpect(status().isOk());
        }
    }
}
