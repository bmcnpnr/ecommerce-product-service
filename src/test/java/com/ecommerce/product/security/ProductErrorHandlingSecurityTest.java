package com.ecommerce.product.security;

import com.ecommerce.product.controller.CategoryController;
import com.ecommerce.product.controller.ProductController;
import com.ecommerce.product.exception.InvalidRequestException;
import com.ecommerce.product.service.ProductService;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Error bodies must not reveal internals: stack traces, exception/class names, package
 * names, SQL. Malformed requests are client errors (4xx), never 500.
 */
@Tag("security")
@WebMvcTest(controllers = {ProductController.class, CategoryController.class})
class ProductErrorHandlingSecurityTest {

    private static final String[] LEAK_MARKERS = {
            "java.", "javax.", "jakarta.", "com.ecommerce", "org.springframework", "org.hibernate", "org.postgresql",
            "com.fasterxml", "tools.jackson", "Exception", "\tat ", "stackTrace", "SELECT ", "INSERT ", "FROM products"
    };

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ProductService productService;

    private static void assertNoInternals(String body) {
        for (String marker : LEAK_MARKERS) {
            assertThat(body).as("error body must not contain '%s'", marker).doesNotContain(marker);
        }
    }

    @Test
    void unexpectedFailure_is500WithGenericMessage_noSqlOrClassNames() throws Exception {
        when(productService.getProductById(anyLong())).thenThrow(new DataIntegrityViolationException(
                "could not execute statement [ERROR: duplicate key value violates unique constraint \"products_sku_key\"] "
                        + "[insert into products (sku) values (?)]; org.postgresql.util.PSQLException"));

        MvcResult result = mockMvc.perform(get("/api/v1/products/1")).andExpect(status().isInternalServerError()).andReturn();

        String body = result.getResponse().getContentAsString();
        assertThat(body).contains("An unexpected error occurred");
        assertNoInternals(body);
        assertThat(body).doesNotContain("products_sku_key").doesNotContain("insert into");
        assertThat(result.getResponse().getContentType()).startsWith(MediaType.APPLICATION_JSON_VALUE);
    }

    @Test
    void malformedJson_is400_withoutParserInternals() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/products").header("X-User-Role", "ADMIN").contentType(MediaType.APPLICATION_JSON)
                .content("{\"sku\": \"A\", \"name\": ")).andExpect(status().isBadRequest()).andReturn();

        assertNoInternals(result.getResponse().getContentAsString());
        verifyNoInteractions(productService);
    }

    @Test
    void wrongFieldType_is400_withoutClassNames() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/products").header("X-User-Role", "ADMIN").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sku\":\"A\",\"name\":\"n\",\"price\":\"not-a-number\",\"stockQuantity\":1}"))
                .andExpect(status().isBadRequest()).andReturn();

        assertNoInternals(result.getResponse().getContentAsString());
        verifyNoInteractions(productService);
    }

    @Test
    void nonNumericPathVariable_is400_withoutClassNames() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/v1/products/abc")).andExpect(status().isBadRequest()).andReturn();

        assertNoInternals(result.getResponse().getContentAsString());
        verifyNoInteractions(productService);
    }

    @Test
    void overflowingIdPathVariable_is400() throws Exception {
        mockMvc.perform(get("/api/v1/products/99999999999999999999999")).andExpect(status().isBadRequest());
        verifyNoInteractions(productService);
    }

    @Test
    void missingRequiredSearchParameter_is400() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/v1/products/search")).andExpect(status().isBadRequest()).andReturn();

        assertNoInternals(result.getResponse().getContentAsString());
    }

    @Test
    void wrongContentType_isClientError415_not500() throws Exception {
        mockMvc.perform(post("/api/v1/products").header("X-User-Role", "ADMIN").contentType(MediaType.TEXT_PLAIN).content("sku=A&name=n"))
                .andExpect(status().isUnsupportedMediaType());
        verifyNoInteractions(productService);
    }

    @Test
    void xmlBody_isRejectedAs415_not500() throws Exception {
        mockMvc.perform(post("/api/v1/categories").header("X-User-Role", "ADMIN").contentType(MediaType.APPLICATION_XML)
                        .content("<!DOCTYPE x [<!ENTITY e SYSTEM \"file:///etc/passwd\">]><c><name>&e;</name></c>"))
                .andExpect(status().isUnsupportedMediaType());
        verifyNoInteractions(productService);
    }

    @Test
    void unsupportedMethod_is405() throws Exception {
        mockMvc.perform(put("/api/v1/categories/1").header("X-User-Role", "ADMIN").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isMethodNotAllowed());
        mockMvc.perform(delete("/api/v1/categories/1").header("X-User-Role", "ADMIN")).andExpect(status().isMethodNotAllowed());
    }

    @Test
    void unknownPath_is404_not500() throws Exception {
        mockMvc.perform(get("/api/v1/nope")).andExpect(status().isNotFound());
    }

    @ParameterizedTest(name = "price {0} is rejected with 400")
    @ValueSource(strings = {"-1", "-0.01", "0", "0.00", "0.001", "-999999999999"})
    void createProduct_invalidPrice_is400(String price) throws Exception {
        mockMvc.perform(post("/api/v1/products").header("X-User-Role", "ADMIN").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sku\":\"A\",\"name\":\"n\",\"price\":" + price + ",\"stockQuantity\":1}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(productService);
    }

    @ParameterizedTest(name = "price literal {0} is rejected with 400")
    @ValueSource(strings = {"\"NaN\"", "\"Infinity\"", "\"-Infinity\"", "null", "\"\"", "[1]", "{}"})
    void createProduct_nonNumericOrMissingPrice_is400(String priceLiteral) throws Exception {
        mockMvc.perform(post("/api/v1/products").header("X-User-Role", "ADMIN").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sku\":\"A\",\"name\":\"n\",\"price\":" + priceLiteral + ",\"stockQuantity\":1}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(productService);
    }

    @ParameterizedTest(name = "update price {0} is rejected with 400")
    @ValueSource(strings = {"-5", "0", "0.001"})
    void updateProduct_invalidPrice_is400(String price) throws Exception {
        mockMvc.perform(put("/api/v1/products/1").header("X-User-Role", "ADMIN").contentType(MediaType.APPLICATION_JSON).content("{\"price\":" + price + "}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(productService);
    }

    @ParameterizedTest(name = "stockQuantity {0} is rejected with 400")
    @ValueSource(strings = {"-1", "-2147483648", "2147483648", "99999999999999999999", "\"ten\""})
    void createProduct_invalidStock_is400(String stock) throws Exception {
        mockMvc.perform(post("/api/v1/products").header("X-User-Role", "ADMIN").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sku\":\"A\",\"name\":\"n\",\"price\":1.00,\"stockQuantity\":" + stock + "}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(productService);
    }

    @ParameterizedTest(name = "stock delta {0} is rejected with 400")
    @ValueSource(strings = {"null", "2147483648", "-2147483649", "\"abc\""})
    void stockDelta_invalid_is400(String delta) throws Exception {
        mockMvc.perform(patch("/api/v1/products/1/stock").header("X-User-Role", "ADMIN").contentType(MediaType.APPLICATION_JSON).content("{\"delta\":" + delta + "}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(productService);
    }

    @ParameterizedTest(name = "fractional stock delta {0} is rejected, not silently truncated")
    @ValueSource(strings = {"1.5", "-1.9", "0.999"})
    void stockDelta_fractional_is400(String delta) throws Exception {
        mockMvc.perform(patch("/api/v1/products/1/stock").header("X-User-Role", "ADMIN").contentType(MediaType.APPLICATION_JSON).content("{\"delta\":" + delta + "}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(productService);
    }

    @Test
    void overlongFields_areRejectedAtTheEdge() throws Exception {
        String longName = "x".repeat(201);
        mockMvc.perform(post("/api/v1/products").header("X-User-Role", "ADMIN").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sku\":\"A\",\"name\":\"" + longName + "\",\"price\":1.00,\"stockQuantity\":1}"))
                .andExpect(status().isBadRequest());
        String longSku = "s".repeat(101);
        mockMvc.perform(post("/api/v1/products").header("X-User-Role", "ADMIN").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sku\":\"" + longSku + "\",\"name\":\"n\",\"price\":1.00,\"stockQuantity\":1}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(productService);
    }

    @Test
    void invalidStatusValue_is400_withoutInternals() throws Exception {
        when(productService.updateProduct(anyLong(), any())).thenThrow(new InvalidRequestException("Unknown product status: X"));
        MvcResult r = mockMvc.perform(put("/api/v1/products/1").header("X-User-Role", "ADMIN").contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"X\"}"))
                .andExpect(status().isBadRequest()).andReturn();
        assertNoInternals(r.getResponse().getContentAsString());
    }
}
