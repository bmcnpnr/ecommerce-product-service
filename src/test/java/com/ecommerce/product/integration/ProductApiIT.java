package com.ecommerce.product.integration;

import com.ecommerce.product.dto.CategoryDTO;
import com.ecommerce.product.dto.ProductDTO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.client.RestTestClient;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The whole service over HTTP — filters, controllers, validation, exception
 * handler, transactions, JPA — on PostgreSQL with the production Flyway schema.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "eureka.client.enabled=false",
        "spring.cloud.discovery.enabled=false",
        "management.tracing.sampling.probability=0.0"
})
class ProductApiIT extends PostgresIntegrationTestSupport {

    @LocalServerPort
    private int port;

    @Autowired
    private JdbcTemplate jdbc;

    private RestTestClient client;

    @BeforeEach
    void setUp() {
        jdbc.execute("TRUNCATE products, categories RESTART IDENTITY CASCADE");
        client = RestTestClient.bindToServer().baseUrl("http://localhost:" + port).build();
    }

    @Test
    void catalogFlow_categoryProductSearchPageAndStock() {
        // categories, incl. a child
        CategoryDTO electronics = createCategory("Electronics", null);
        CategoryDTO mice = createCategory("Mice", electronics.getId());
        assertThat(mice.getParentCategoryId()).isEqualTo(electronics.getId());

        client.get().uri("/api/v1/categories").exchange()
                .expectStatus().isOk()
                .expectBody().jsonPath("$.length()").isEqualTo(2);
        client.get().uri("/api/v1/categories/{id}", mice.getId()).exchange()
                .expectStatus().isOk()
                .expectBody().jsonPath("$.name").isEqualTo("Mice");

        // products
        ProductDTO mouse = createProduct("WM-1", "Wireless Mouse", "19.99", 10, mice.getId());
        createProduct("WK-1", "Wireless Keyboard", "49.50", 3, electronics.getId());
        createProduct("HUB-1", "USB Hub", "12.00", 7, electronics.getId());
        assertThat(mouse.getStatus()).isEqualTo("ACTIVE");
        assertThat(mouse.getPrice()).isEqualByComparingTo("19.99");

        client.get().uri("/api/v1/products/{id}", mouse.getId()).exchange()
                .expectStatus().isOk()
                .expectBody().jsonPath("$.sku").isEqualTo("WM-1");
        client.get().uri("/api/v1/products/sku/{sku}", "WK-1").exchange()
                .expectStatus().isOk()
                .expectBody().jsonPath("$.name").isEqualTo("Wireless Keyboard");

        // search + pagination + sort
        client.get().uri("/api/v1/products/search?q=WIRELESS&page=0&size=1&sort=name").exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.content.length()").isEqualTo(1)
                .jsonPath("$.content[0].name").isEqualTo("Wireless Keyboard")
                .jsonPath("$..totalElements").isEqualTo(List.of(2));
        client.get().uri("/api/v1/products/search?q=WIRELESS&page=1&size=1&sort=name").exchange()
                .expectStatus().isOk()
                .expectBody().jsonPath("$.content[0].name").isEqualTo("Wireless Mouse");
        client.get().uri("/api/v1/products?size=2&sort=sku,desc").exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.content[0].sku").isEqualTo("WM-1")
                .jsonPath("$..totalElements").isEqualTo(List.of(3));
        client.get().uri("/api/v1/products/category/{id}", electronics.getId()).exchange()
                .expectStatus().isOk()
                .expectBody().jsonPath("$.length()").isEqualTo(2);

        // stock: decrement, drain, insufficient, restock
        patchStock(mouse.getId(), -4).expectStatus().isOk()
                .expectBody().jsonPath("$.stockQuantity").isEqualTo(6);
        patchStock(mouse.getId(), -7).expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.status").isEqualTo(400)
                .jsonPath("$.message").isEqualTo("Insufficient stock for product WM-1. Available: 6, Requested: 7");
        patchStock(mouse.getId(), -6).expectStatus().isOk()
                .expectBody()
                .jsonPath("$.stockQuantity").isEqualTo(0)
                .jsonPath("$.status").isEqualTo("OUT_OF_STOCK");
        client.get().uri("/api/v1/products/search?q=wireless").exchange()
                .expectStatus().isOk()
                .expectBody().jsonPath("$.content.length()").isEqualTo(1);
        patchStock(mouse.getId(), 5).expectStatus().isOk()
                .expectBody()
                .jsonPath("$.stockQuantity").isEqualTo(5)
                .jsonPath("$.status").isEqualTo("ACTIVE");

        // update + soft delete
        client.put().uri("/api/v1/products/{id}", mouse.getId())
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("price", new BigDecimal("17.25"), "name", "Wireless Mouse v2"))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.name").isEqualTo("Wireless Mouse v2")
                .jsonPath("$.updatedAt").exists();
        client.delete().uri("/api/v1/products/{id}", mouse.getId()).exchange()
                .expectStatus().isNoContent();
        client.get().uri("/api/v1/products/{id}", mouse.getId()).exchange()
                .expectStatus().isOk()
                .expectBody().jsonPath("$.status").isEqualTo("INACTIVE");
        client.get().uri("/api/v1/products").exchange()
                .expectStatus().isOk()
                .expectBody().jsonPath("$..totalElements").isEqualTo(List.of(2));
    }

    @Test
    void errorResponses() {
        CategoryDTO cat = createCategory("Books", null);
        createProduct("BOOK-1", "Novel", "9.99", 1, cat.getId());

        client.post().uri("/api/v1/products").contentType(MediaType.APPLICATION_JSON)
                .header("X-Correlation-Id", "it-corr-1")
                .body(productBody("BOOK-1", "Another", "1.00", 1, null))
                .exchange()
                .expectStatus().isEqualTo(409)
                .expectHeader().valueEquals("X-Correlation-Id", "it-corr-1")
                .expectBody()
                .jsonPath("$.message").isEqualTo("Product with SKU already exists: BOOK-1")
                .jsonPath("$.correlationId").isEqualTo("it-corr-1");
        client.post().uri("/api/v1/products").contentType(MediaType.APPLICATION_JSON)
                .body(productBody("BOOK-2", "Orphan", "1.00", 1, 999L))
                .exchange()
                .expectStatus().isNotFound()
                .expectBody().jsonPath("$.message").isEqualTo("Category not found: 999");
        client.post().uri("/api/v1/categories").contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("name", "Books"))
                .exchange()
                .expectStatus().isEqualTo(409);
        client.post().uri("/api/v1/products").contentType(MediaType.APPLICATION_JSON)
                .body(productBody("", "", "0", -1, null))
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.message").isEqualTo("Validation failed")
                .jsonPath("$.fieldErrors.length()").isEqualTo(4);
        client.get().uri("/api/v1/products/12345").exchange().expectStatus().isNotFound();
        client.get().uri("/api/v1/products/sku/NOPE").exchange().expectStatus().isNotFound();
        client.get().uri("/api/v1/categories/12345").exchange().expectStatus().isNotFound();
        client.get().uri("/api/v1/products/category/12345").exchange().expectStatus().isNotFound();
        patchStock(12345L, -1).expectStatus().isNotFound();
    }

    /**
     * Stock is read, checked and written back with no lock or version column, so
     * concurrent decrements can each see the same quantity: more units are sold
     * than exist (and the final stock no longer matches the number of successful
     * sales).
     */
    @Test
    void concurrentDecrements_neverOversell() throws Exception {
        ProductDTO product = createProduct("HOT-1", "Limited Edition", "99.00", 10, null);
        int buyers = 40;

        HttpClient http = HttpClient.newHttpClient();
        ExecutorService pool = Executors.newFixedThreadPool(buyers);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> results = new ArrayList<>();
        for (int i = 0; i < buyers; i++) {
            results.add(pool.submit(() -> {
                start.await();
                HttpRequest req = HttpRequest.newBuilder(URI.create(
                                "http://localhost:" + port + "/api/v1/products/" + product.getId() + "/stock"))
                        .header("Content-Type", "application/json")
                        .method("PATCH", HttpRequest.BodyPublishers.ofString("{\"delta\":-1}"))
                        .build();
                return http.send(req, HttpResponse.BodyHandlers.discarding()).statusCode();
            }));
        }
        start.countDown();
        int sold = 0;
        for (Future<Integer> f : results) {
            if (f.get() == 200) sold++;
        }
        pool.shutdown();

        Integer remaining = jdbc.queryForObject("SELECT stock_quantity FROM products WHERE id = ?", Integer.class, product.getId());
        assertThat(sold).as("successful decrements").isEqualTo(10);
        assertThat(remaining).isZero();
    }

    /** Serial decrements are fine — the oversell above is purely a concurrency defect. */
    @Test
    void sequentialDecrements_stopExactlyAtZero() {
        ProductDTO product = createProduct("SEQ-1", "Sequential", "5.00", 3, null);
        for (int i = 0; i < 3; i++) {
            patchStock(product.getId(), -1).expectStatus().isOk();
        }
        patchStock(product.getId(), -1).expectStatus().isBadRequest();
        assertThat(jdbc.queryForObject("SELECT stock_quantity FROM products WHERE id = ?", Integer.class, product.getId()))
                .isZero();
    }

    @Test
    void updateProduct_unknownStatus_is400() {
        ProductDTO product = createProduct("ST-1", "Status", "5.00", 3, null);

        client.put().uri("/api/v1/products/{id}", product.getId())
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("status", "DISCONTINUED"))
                .exchange()
                .expectStatus().isBadRequest();
    }

    @Test
    void updateProduct_unknownCategory_is404() {
        ProductDTO product = createProduct("CAT-1", "Category", "5.00", 3, null);

        client.put().uri("/api/v1/products/{id}", product.getId())
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("categoryId", 424242))
                .exchange()
                .expectStatus().isNotFound();
    }

    @Test
    void createCategory_unknownParent_is404() {
        client.post().uri("/api/v1/categories").contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("name", "Orphans", "parentCategoryId", 424242))
                .exchange()
                .expectStatus().isNotFound();
    }

    // ───────────────────────── helpers ─────────────────────────

    private CategoryDTO createCategory(String name, Long parentId) {
        Map<String, Object> body = new HashMap<>();
        body.put("name", name);
        body.put("description", name + " category");
        body.put("parentCategoryId", parentId);
        return client.post().uri("/api/v1/categories").contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .exchange()
                .expectStatus().isCreated()
                .expectBody(CategoryDTO.class).returnResult().getResponseBody();
    }

    private ProductDTO createProduct(String sku, String name, String price, int stock, Long categoryId) {
        return client.post().uri("/api/v1/products").contentType(MediaType.APPLICATION_JSON)
                .body(productBody(sku, name, price, stock, categoryId))
                .exchange()
                .expectStatus().isCreated()
                .expectBody(ProductDTO.class).returnResult().getResponseBody();
    }

    private static Map<String, Object> productBody(String sku, String name, String price, int stock, Long categoryId) {
        Map<String, Object> body = new HashMap<>();
        body.put("sku", sku);
        body.put("name", name);
        body.put("price", new BigDecimal(price));
        body.put("stockQuantity", stock);
        body.put("brand", "Acme");
        body.put("categoryId", categoryId);
        return body;
    }

    private RestTestClient.ResponseSpec patchStock(Long id, int delta) {
        return client.patch().uri("/api/v1/products/{id}/stock", id)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("delta", delta))
                .exchange();
    }
}
