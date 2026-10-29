package com.ecommerce.product.contract;

import au.com.dius.pact.provider.junit5.HttpTestTarget;
import au.com.dius.pact.provider.junit5.PactVerificationContext;
import au.com.dius.pact.provider.junit5.PactVerificationInvocationContextProvider;
import au.com.dius.pact.provider.junitsupport.Provider;
import au.com.dius.pact.provider.junitsupport.State;
import au.com.dius.pact.provider.junitsupport.loader.PactFolder;
import org.apache.hc.core5.http.Header;
import org.apache.hc.core5.http.HttpRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.TestTemplate;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Provider side of every HTTP contract product-service has with its callers.
 *
 * <p>Each consumer (order-service, catalog-service, review-service, all via Feign)
 * owns a consumer test that records what it sends and which response fields it
 * relies on, and writes that as a pact. The pact files are copied into
 * {@code src/test/resources/pacts/} (see {@code ecommerce-platform/sync-pacts.sh})
 * and this class replays every interaction against the real application —
 * controllers, validation, {@code GlobalExceptionHandler}, JPA on H2 — started on a
 * random port. A failure here means a change in this service would break the named
 * consumer in production.
 *
 * <p>Provider states are set up with plain SQL because the consumers address
 * products by id in their request paths; JPA's IDENTITY generation would not let
 * the test choose the id.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Provider("product-service")
@PactFolder("pacts")
class ProductServiceProviderPactTest {

    @LocalServerPort
    private int port;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void setTarget(PactVerificationContext context) {
        context.setTarget(new HttpTestTarget("localhost", port));
    }

    @TestTemplate
    @ExtendWith(PactVerificationInvocationContextProvider.class)
    void verifyPact(PactVerificationContext context) {
        context.verifyInteraction();
    }

    // ───────────────────────── provider states ─────────────────────────
    // State names are part of the contract: consumers reference them verbatim.

    /** Category 10 "Electronics" and product 1 (SKU-001, 49.99, 50 in stock, category 10). */
    @State("product 1 exists")
    void product1Exists() {
        clearTables();
        insertCategory(10L, "Electronics", "Devices and accessories", null);
        insertProduct(1L, "SKU-001", "Wireless Mouse", "Ergonomic wireless mouse",
                new BigDecimal("49.99"), 50, "Logi", 10L, "ACTIVE");
    }

    @State("product 999 does not exist")
    void product999DoesNotExist() {
        jdbc.update("DELETE FROM products WHERE id = 999");
    }

    /** Categories 10 "Electronics" and 11 "Accessories" (child of 10). */
    @State("categories exist")
    void categoriesExist() {
        // Rows inserted with explicit ids do not advance the IDENTITY counter, so a leftover product 1 would make
        // the next generated id (POST /products SKU-NEW-1) collide. Also guarantees no "Gadgets" and no SKU-NEW-1.
        jdbc.update("DELETE FROM products");
        jdbc.update("DELETE FROM categories WHERE id NOT IN (10, 11)");
        jdbc.update("DELETE FROM categories WHERE id IN (10, 11)");
        insertCategory(10L, "Electronics", "Devices and accessories", null);
        insertCategory(11L, "Accessories", "Mice, keyboards, cables", 10L);
    }

    private void clearTables() {
        jdbc.update("DELETE FROM products");
        jdbc.update("DELETE FROM categories");
    }

    private void insertCategory(Long id, String name, String description, Long parentId) {
        jdbc.update("INSERT INTO categories (id, name, description, parent_category_id, created_at) VALUES (?, ?, ?, ?, ?)",
                id, name, description, parentId, LocalDateTime.now());
    }

    private void insertProduct(Long id, String sku, String name, String description, BigDecimal price,
                               int stock, String brand, Long categoryId, String status) {
        jdbc.update("INSERT INTO products (id, sku, name, description, price, stock_quantity, brand, category_id, status, created_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                id, sku, name, description, price, stock, brand, categoryId, status, LocalDateTime.now());
    }
}
