package com.ecommerce.product.integration;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.client.RestTestClient;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;

/**
 * Shared harness of the security ITs: same {@code @SpringBootTest} configuration as
 * {@link ProductApiIT} (so the cached application context and the Postgres container are
 * reused), a truncated schema before every test, and request helpers.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "eureka.client.enabled=false",
        "spring.cloud.discovery.enabled=false",
        "management.tracing.sampling.probability=0.0"
})
abstract class ProductSecurityITSupport extends PostgresIntegrationTestSupport {

    @LocalServerPort
    private int port;

    @Autowired
    protected JdbcTemplate jdbc;

    protected RestTestClient client;

    @BeforeEach
    void resetState() {
        jdbc.execute("TRUNCATE products, categories RESTART IDENTITY CASCADE");
        client = RestTestClient.bindToServer().baseUrl("http://localhost:" + port).build();
    }

    protected static Map<String, Object> productBody(String sku, String name, String price, int stock) {
        Map<String, Object> body = new HashMap<>();
        body.put("sku", sku);
        body.put("name", name);
        body.put("price", new BigDecimal(price));
        body.put("stockQuantity", stock);
        body.put("brand", "Acme");
        return body;
    }

    /** Creates a product through the API and returns its generated id (read back from the table). */
    protected long createProduct(String sku, String name, String price, int stock) {
        client.post().uri("/api/v1/products").contentType(MediaType.APPLICATION_JSON)
                .header("X-User-Role", "ADMIN")
                .body(productBody(sku, name, price, stock))
                .exchange()
                .expectStatus().isCreated();
        return jdbc.queryForObject("SELECT id FROM products WHERE sku = ?", Long.class, sku);
    }

    protected int countProducts() {
        return jdbc.queryForObject("SELECT count(*) FROM products", Integer.class);
    }
}
