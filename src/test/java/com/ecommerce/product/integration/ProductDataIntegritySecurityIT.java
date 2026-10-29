package com.ecommerce.product.integration;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Authorization effect on the database, stock arithmetic, stored XSS, mass assignment,
 * price tampering and header handling, against PostgreSQL with the production schema.
 */
@Tag("security")
class ProductDataIntegritySecurityIT extends ProductSecurityITSupport {

    private int stockOf(long id) {
        return jdbc.queryForObject("SELECT stock_quantity FROM products WHERE id = ?", Integer.class, id);
    }

    private String statusOf(long id) {
        return jdbc.queryForObject("SELECT status FROM products WHERE id = ?", String.class, id);
    }

    private void patchStock(long id, Object delta, int expectedStatus) {
        client.patch().uri("/api/v1/products/{id}/stock", id).contentType(MediaType.APPLICATION_JSON)
                .body("{\"delta\":" + delta + "}").exchange().expectStatus().isEqualTo(expectedStatus);
    }

    // ───────────────────────── authorization (S-1), with database proof ─────────────────────────

    @Disabled("BUG S-1: a CUSTOMER (or no role at all) can create, edit, adjust stock of and delete products; the database changes")
    @Test
    void customer_cannotModifyCatalog_andDatabaseIsUntouched() {
        long id = createProduct("AUTH-1", "Original", "10.00", 5);

        client.post().uri("/api/v1/products").contentType(MediaType.APPLICATION_JSON)
                .header("X-User-Role", "CUSTOMER").header("X-Username", "mallory")
                .body(productBody("AUTH-2", "Planted", "1.00", 1)).exchange().expectStatus().isForbidden();
        client.put().uri("/api/v1/products/{id}", id).contentType(MediaType.APPLICATION_JSON)
                .header("X-User-Role", "CUSTOMER").body(Map.of("name", "Hijacked", "price", new BigDecimal("0.01")))
                .exchange().expectStatus().isForbidden();
        client.patch().uri("/api/v1/products/{id}/stock", id).contentType(MediaType.APPLICATION_JSON)
                .header("X-User-Role", "CUSTOMER").body(Map.of("delta", -5)).exchange().expectStatus().isForbidden();
        client.delete().uri("/api/v1/products/{id}", id).header("X-User-Role", "CUSTOMER").exchange().expectStatus().isForbidden();
        client.post().uri("/api/v1/categories").contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("name", "Planted")).exchange().expectStatus().isForbidden();

        assertThat(countProducts()).isEqualTo(1);
        assertThat(jdbc.queryForMap("SELECT name, price, stock_quantity, status FROM products WHERE id = ?", id))
                .containsEntry("name", "Original").containsEntry("stock_quantity", 5).containsEntry("status", "ACTIVE");
        assertThat(jdbc.queryForObject("SELECT price FROM products WHERE id = ?", BigDecimal.class, id)).isEqualByComparingTo("10.00");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM categories", Integer.class)).isZero();
    }

    @Test
    void reads_arePublic_withoutAnyIdentityHeaders() {
        long id = createProduct("PUB-1", "Public", "10.00", 5);
        client.post().uri("/api/v1/categories").contentType(MediaType.APPLICATION_JSON).body(Map.of("name", "Pub")).exchange().expectStatus().isCreated();

        for (String path : List.of("/api/v1/products", "/api/v1/products/" + id, "/api/v1/products/sku/PUB-1",
                "/api/v1/products/search?q=pub", "/api/v1/categories", "/api/v1/categories/1")) {
            client.get().uri(path).exchange().expectStatus().isOk();
        }
    }

    // ───────────────────────── stock arithmetic ─────────────────────────

    @ParameterizedTest(name = "stock 5, delta {0} is rejected and stock stays 5")
    @ValueSource(strings = {"-6", "-2147483648", "2147483647", "2147483646", "1000000000000"})
    void stockDelta_extremeValues_neverCorruptStock(String delta) {
        long id = createProduct("STK-1", "Stock", "10.00", 5);

        // 5 + 2147483646 and 5 + 2147483647 overflow int; -6 and MIN_VALUE go negative; 1e12 is not an int at all.
        patchStock(id, delta, 400);

        assertThat(stockOf(id)).as("stock after rejected delta %s", delta).isEqualTo(5);
        assertThat(statusOf(id)).isEqualTo("ACTIVE");
    }

    @Test
    void stockAtIntMax_cannotBeIncremented_andWrapAroundNeverStored() {
        long id = createProduct("STK-MAX", "Max", "10.00", Integer.MAX_VALUE);

        patchStock(id, 1, 400);
        patchStock(id, Integer.MAX_VALUE, 400);

        assertThat(stockOf(id)).isEqualTo(Integer.MAX_VALUE);
        patchStock(id, -1, 200);
        assertThat(stockOf(id)).isEqualTo(Integer.MAX_VALUE - 1);
        patchStock(id, 1, 200);
        assertThat(stockOf(id)).isEqualTo(Integer.MAX_VALUE);
    }

    @Test
    void stockDeltaZero_isNoOp_andNeverMakesAProductSellable_orInactive() {
        long id = createProduct("STK-0", "Zero", "10.00", 0);
        jdbc.update("UPDATE products SET status = 'OUT_OF_STOCK' WHERE id = ?", id);

        patchStock(id, 0, 200);

        assertThat(stockOf(id)).isZero();
        assertThat(statusOf(id)).isEqualTo("OUT_OF_STOCK");
    }

    @Test
    void stock_cannotBeAdjustedBelowZero_byManySmallDecrements() {
        long id = createProduct("STK-2", "Few", "10.00", 3);

        for (int i = 0; i < 3; i++) patchStock(id, -1, 200);
        patchStock(id, -1, 400);

        assertThat(stockOf(id)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM products WHERE stock_quantity < 0", Integer.class)).isZero();
    }

    // ───────────────────────── stored XSS / output encoding ─────────────────────────

    private static final String[] XSS = {
            "<script>alert(1)</script>",
            "<img src=x onerror=alert(document.cookie)>",
            "\"><svg/onload=alert(1)>",
            "javascript:alert(1)",
            "</script><script>alert(2)</script>",
    };

    @Test
    void xssPayloads_roundTripAsInertJson_neverAsHtml() {
        for (int i = 0; i < XSS.length; i++) {
            Map<String, Object> body = productBody("XSS-" + i, XSS[i], "5.00", 1);
            body.put("description", XSS[i]);
            body.put("brand", XSS[i].substring(0, Math.min(20, XSS[i].length())));
            client.post().uri("/api/v1/products").contentType(MediaType.APPLICATION_JSON).body(body).exchange()
                    .expectStatus().isCreated()
                    .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_JSON)
                    .expectBody().jsonPath("$.name").isEqualTo(XSS[i]).jsonPath("$.description").isEqualTo(XSS[i]);
        }
        for (String path : List.of("/api/v1/products", "/api/v1/products/search?q=script", "/api/v1/products/sku/XSS-0", "/api/v1/products/1")) {
            client.get().uri(path).accept(MediaType.TEXT_HTML, MediaType.APPLICATION_JSON).exchange()
                    .expectStatus().isOk()
                    .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_JSON);
        }
        // stored verbatim (not "sanitised" into something else, not lost)
        assertThat(jdbc.queryForObject("SELECT name FROM products WHERE sku = 'XSS-0'", String.class)).isEqualTo(XSS[0]);
    }

    @Test
    void errorResponses_reflectingInput_areJsonNotHtml() {
        client.get().uri("/api/v1/products/sku/{sku}", "<img onerror=alert(1)>").accept(MediaType.TEXT_HTML, MediaType.APPLICATION_JSON).exchange()
                .expectStatus().isNotFound()
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_JSON);
        client.get().uri("/api/v1/products/<img src=x onerror=alert(1)>").exchange()
                .expectStatus().is4xxClientError()
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_JSON);
    }

    @Disabled("BUG S-43 (low): responses carry no X-Content-Type-Options: nosniff, so a browser may sniff stored markup in JSON bodies reached directly (compose publishes the port)")
    @Test
    void jsonResponses_sendNosniff() {
        createProduct("NS-1", "<script>alert(1)</script>", "5.00", 1);
        client.get().uri("/api/v1/products/sku/NS-1").exchange().expectStatus().isOk()
                .expectHeader().valueEquals("X-Content-Type-Options", "nosniff");
    }

    // ───────────────────────── mass assignment ─────────────────────────

    @Test
    void create_cannotOverrideServerControlledFields() {
        Map<String, Object> body = productBody("MASS-1", "Mass", "10.00", 5);
        body.put("id", 9999);
        body.put("status", "INACTIVE");
        body.put("createdAt", "2000-01-01T00:00:00");
        body.put("updatedAt", "2000-01-01T00:00:00");
        body.put("version", 42);
        body.put("role", "ADMIN");
        body.put("isAdmin", true);

        client.post().uri("/api/v1/products").contentType(MediaType.APPLICATION_JSON).body(body).exchange()
                .expectStatus().isCreated()
                .expectBody()
                .jsonPath("$.id").value(id -> assertThat(((Number) id).longValue()).isNotEqualTo(9999L))
                .jsonPath("$.status").isEqualTo("ACTIVE");

        Map<String, Object> row = jdbc.queryForMap("SELECT id, status, created_at FROM products WHERE sku = 'MASS-1'");
        assertThat(((Number) row.get("id")).longValue()).isNotEqualTo(9999L);
        assertThat(row.get("status")).isEqualTo("ACTIVE");
        assertThat(row.get("created_at").toString()).doesNotStartWith("2000-01-01");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM products WHERE id = 9999", Integer.class)).isZero();
    }

    @Test
    void update_cannotChangeIdSkuStockOrCreatedAt() {
        long id = createProduct("MASS-2", "Before", "10.00", 5);
        Object createdAt = jdbc.queryForObject("SELECT created_at FROM products WHERE id = ?", Object.class, id);

        Map<String, Object> body = new HashMap<>();
        body.put("name", "After");
        body.put("id", 777);
        body.put("sku", "STOLEN-SKU");
        body.put("stockQuantity", 1_000_000);
        body.put("createdAt", "1999-01-01T00:00:00");
        client.put().uri("/api/v1/products/{id}", id).contentType(MediaType.APPLICATION_JSON).body(body).exchange()
                .expectStatus().isOk()
                .expectBody().jsonPath("$.id").isEqualTo((int) id).jsonPath("$.sku").isEqualTo("MASS-2").jsonPath("$.name").isEqualTo("After");

        assertThat(jdbc.queryForMap("SELECT sku, stock_quantity FROM products WHERE id = ?", id))
                .containsEntry("sku", "MASS-2").containsEntry("stock_quantity", 5);
        assertThat(jdbc.queryForObject("SELECT created_at FROM products WHERE id = ?", Object.class, id)).isEqualTo(createdAt);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM products WHERE id = 777", Integer.class)).isZero();
        assertThat(countProducts()).isEqualTo(1);
    }

    @Test
    void createCategory_cannotChooseItsOwnId() {
        client.post().uri("/api/v1/categories").contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("name", "C1", "id", 500, "createdAt", "2000-01-01T00:00:00")).exchange().expectStatus().isCreated();

        assertThat(jdbc.queryForObject("SELECT count(*) FROM categories WHERE id = 500", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT created_at FROM categories WHERE name = 'C1'", Object.class).toString()).doesNotStartWith("2000");
    }

    // ───────────────────────── soft delete / visibility ─────────────────────────

    @Disabled("BUG S-44 (low): a soft-deleted (INACTIVE) product is still served by GET /products/{id} and /products/sku/{sku}, though listing, search and category views hide it")
    @Test
    void softDeletedProduct_isInvisibleToPublicReads() {
        long id = createProduct("DEL-1", "Withdrawn", "10.00", 5);
        client.delete().uri("/api/v1/products/{id}", id).header("X-User-Role", "ADMIN").exchange().expectStatus().isNoContent();

        client.get().uri("/api/v1/products/{id}", id).exchange().expectStatus().isNotFound();
        client.get().uri("/api/v1/products/sku/DEL-1").exchange().expectStatus().isNotFound();
    }

    @Test
    void softDeletedProduct_isHiddenFromListSearchAndCategory() {
        long id = createProduct("DEL-2", "Withdrawn Thing", "10.00", 5);
        client.delete().uri("/api/v1/products/{id}", id).header("X-User-Role", "ADMIN").exchange().expectStatus().isNoContent();

        assertThat(statusOf(id)).isEqualTo("INACTIVE");
        client.get().uri("/api/v1/products").exchange().expectBody().jsonPath("$.totalElements").isEqualTo(0);
        client.get().uri("/api/v1/products/search?q=withdrawn").exchange().expectBody().jsonPath("$.totalElements").isEqualTo(0);
    }

    // ───────────────────────── price tampering ─────────────────────────

    @ParameterizedTest(name = "price {0} cannot reach the database")
    @ValueSource(strings = {"-10", "0", "0.001", "-0.01"})
    void invalidPrices_areRejected_andNothingIsStored(String price) {
        client.post().uri("/api/v1/products").contentType(MediaType.APPLICATION_JSON)
                .body("{\"sku\":\"P-1\",\"name\":\"n\",\"price\":" + price + ",\"stockQuantity\":1}").exchange()
                .expectStatus().isBadRequest();
        assertThat(countProducts()).isZero();
    }

    @Test
    void updatePrice_toNegativeOrZero_isRejected_andPriceUnchanged() {
        long id = createProduct("P-2", "Priced", "10.00", 1);
        for (String bad : List.of("-5", "0", "-0.01")) {
            client.put().uri("/api/v1/products/{id}", id).contentType(MediaType.APPLICATION_JSON)
                    .body("{\"price\":" + bad + "}").exchange().expectStatus().isBadRequest();
        }
        assertThat(jdbc.queryForObject("SELECT price FROM products WHERE id = ?", BigDecimal.class, id)).isEqualByComparingTo("10.00");
    }

    @Disabled("BUG S-48: a price that exceeds NUMERIC(19,4) (e.g. 99999999999999999999.99) passes validation and PostgreSQL rejects it, answering 500 instead of 400")
    @Test
    void priceBeyondColumnCapacity_is400() {
        client.post().uri("/api/v1/products").contentType(MediaType.APPLICATION_JSON)
                .body("{\"sku\":\"P-BIG\",\"name\":\"n\",\"price\":99999999999999999999.99,\"stockQuantity\":1}").exchange()
                .expectStatus().isBadRequest();
        assertThat(countProducts()).isZero();
    }

    @Disabled("BUG S-48 (low): prices with more than 4 decimals are silently rounded by PostgreSQL (1.23456 -> 1.2346) instead of being rejected")
    @Test
    void priceWithExcessPrecision_isRejected_notSilentlyRounded() {
        client.post().uri("/api/v1/products").contentType(MediaType.APPLICATION_JSON)
                .body("{\"sku\":\"P-PREC\",\"name\":\"n\",\"price\":1.23456,\"stockQuantity\":1}").exchange()
                .expectStatus().isBadRequest();
        assertThat(countProducts()).isZero();
    }

    // ───────────────────────── input size / headers ─────────────────────────

    @Test
    void oversizedFields_areRejected_with400_notDatabaseErrors() {
        client.post().uri("/api/v1/products").contentType(MediaType.APPLICATION_JSON)
                .body(productBody("LONG-1", "n".repeat(201), "5.00", 1)).exchange().expectStatus().isBadRequest();
        Map<String, Object> longBrand = productBody("LONG-2", "n", "5.00", 1);
        longBrand.put("brand", "b".repeat(101));
        client.post().uri("/api/v1/products").contentType(MediaType.APPLICATION_JSON).body(longBrand).exchange().expectStatus().isBadRequest();
        client.post().uri("/api/v1/categories").contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("name", "c".repeat(101))).exchange().expectStatus().isBadRequest();
        assertThat(countProducts()).isZero();
    }

    @Test
    void correlationId_headerIsReflected_butCannotInjectAnotherHeader() {
        // CR/LF cannot be sent by a compliant client and Tomcat rejects them; assert what we can: a benign id round-trips once.
        client.get().uri("/api/v1/products").header("X-Correlation-Id", "abc-123").exchange()
                .expectStatus().isOk().expectHeader().valueEquals("X-Correlation-Id", "abc-123");
    }

    @Disabled("BUG S-49: X-Correlation-Id is taken from the client unvalidated (any length/characters) and written to MDC/logs and the response header")
    @Test
    void correlationId_withMarkupOrExcessiveLength_isReplaced() {
        String hostile = "x <script>alert(1)</script> 2026-10-29 ERROR forged log line";
        client.get().uri("/api/v1/products").header("X-Correlation-Id", hostile).exchange()
                .expectStatus().isOk()
                .expectHeader().value("X-Correlation-Id", v -> assertThat(v).isNotEqualTo(hostile));
        String longId = "a".repeat(5000);
        client.get().uri("/api/v1/products").header("X-Correlation-Id", longId).exchange()
                .expectHeader().value("X-Correlation-Id", v -> assertThat(v.length()).isLessThanOrEqualTo(128));
    }
}
