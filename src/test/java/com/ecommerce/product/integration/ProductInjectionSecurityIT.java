package com.ecommerce.product.integration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.client.RestTestClient;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Injection attempts against the real PostgreSQL schema: every payload must be treated as
 * data. Nothing extra may be returned, the tables must survive, and the service must not
 * answer 500.
 */
@Tag("security")
class ProductInjectionSecurityIT extends ProductSecurityITSupport {

    private static final String[] PAYLOADS = {
            "' OR '1'='1",
            "' OR 1=1 --",
            "'; DROP TABLE products;--",
            "'); DELETE FROM products;--",
            "1 UNION SELECT id, sku, name, description, price, stock_quantity, brand, category_id, status, created_at, updated_at FROM products",
            "\" OR \"\"=\"",
            "1; SELECT pg_sleep(5)",
            "\\",
            "'",
    };

    @BeforeEach
    void seed() {
        createProduct("SKU-A", "Wireless Mouse", "19.99", 10);
        createProduct("SKU-B", "Wired Keyboard", "49.50", 5);
        createProduct("SKU-C", "100% Cotton Shirt", "9.00", 3);
        createProduct("SKU-D", "USB_Hub", "12.00", 7);
    }

    private void assertSchemaAndDataIntact() {
        assertThat(countProducts()).isEqualTo(4);
        assertThat(jdbc.queryForObject("SELECT to_regclass('public.products') IS NOT NULL", Boolean.class)).isTrue();
        assertThat(jdbc.queryForObject("SELECT to_regclass('public.categories') IS NOT NULL", Boolean.class)).isTrue();
        assertThat(jdbc.queryForList("SELECT sku FROM products ORDER BY sku", String.class))
                .containsExactly("SKU-A", "SKU-B", "SKU-C", "SKU-D");
    }

    @Test
    void searchQuery_sqlPayloads_matchNothingAndNeverFail() {
        for (String payload : PAYLOADS) {
            client.get().uri("/api/v1/products/search?q={q}", payload).exchange()
                    .expectStatus().isOk()
                    .expectBody()
                    .jsonPath("$.totalElements").isEqualTo(0)
                    .jsonPath("$.content.length()").isEqualTo(0);
        }
        assertSchemaAndDataIntact();
    }

    @Test
    void searchQuery_isStillAWorkingSubstringMatch() {
        client.get().uri("/api/v1/products/search?q={q}", "mouse").exchange()
                .expectStatus().isOk()
                .expectBody().jsonPath("$.totalElements").isEqualTo(1).jsonPath("$.content[0].sku").isEqualTo("SKU-A");
    }

    @Test
    void skuPathVariable_sqlPayloads_are404_andNeverFail() {
        for (String payload : PAYLOADS) {
            client.get().uri("/api/v1/products/sku/{sku}", payload).exchange().expectStatus().value(
                    s -> assertThat(s).as("sku payload %s", payload).isIn(404, 400));
        }
        assertSchemaAndDataIntact();
    }

    @ParameterizedTest(name = "numeric path variable \"{0}\" is a 4xx")
    @ValueSource(strings = {"1 OR 1=1", "1%20UNION%20SELECT%201", "-1", "0", "1.0", "%27", "1e3"})
    void idPathVariables_nonNumeric_are4xx_notServerErrors(String id) {
        for (String base : List.of("/api/v1/products/", "/api/v1/products/category/", "/api/v1/categories/")) {
            client.get().uri(base + id).exchange().expectStatus().value(
                    st -> assertThat(st).as("GET %s%s", base, id).isBetween(400, 499));
        }
        assertSchemaAndDataIntact();
    }

    @Test
    void storedPayloadsInWrites_areKeptAsLiteralData() {
        String evilSku = "x'); DROP TABLE products;--";
        String evilName = "Robert'); DROP TABLE categories;--";
        client.post().uri("/api/v1/products").header("X-User-Role", "ADMIN").contentType(MediaType.APPLICATION_JSON)
                .body(productBody("SKU-E", evilName, "5.00", 1)).exchange().expectStatus().isCreated();
        client.post().uri("/api/v1/products").header("X-User-Role", "ADMIN").contentType(MediaType.APPLICATION_JSON)
                .body(productBody(evilSku, "Plain", "5.00", 1)).exchange().expectStatus().isCreated();
        client.post().uri("/api/v1/categories").header("X-User-Role", "ADMIN").contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("name", "x' OR '1'='1", "description", "'; DROP TABLE products;--")).exchange().expectStatus().isCreated();

        assertThat(jdbc.queryForObject("SELECT name FROM products WHERE sku = 'SKU-E'", String.class)).isEqualTo(evilName);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM products WHERE sku = ?", Integer.class, evilSku)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT description FROM categories WHERE name = 'x'' OR ''1''=''1'", String.class))
                .isEqualTo("'; DROP TABLE products;--");
        assertThat(countProducts()).isEqualTo(6);
        client.get().uri("/api/v1/products/sku/{sku}", evilSku).exchange()
                .expectStatus().isOk().expectBody().jsonPath("$.name").isEqualTo("Plain");
    }

    @ParameterizedTest(name = "LIKE wildcard \"{0}\" in a search is a literal, not a pattern")
    @ValueSource(strings = {"%", "_", "%%", "%o%", "W_re", "\\"})
    void searchQuery_likeWildcards_areEscaped(String q) {
        client.get().uri("/api/v1/products/search?q={q}", q).exchange()
                .expectStatus().isOk()
                .expectBody().jsonPath("$.totalElements").isEqualTo(
                        // the seed only has "100% Cotton Shirt" ('%') and "USB_Hub" ('_') containing these literally
                        switch (q) {
                            case "%" -> 1;
                            case "_" -> 1;
                            default -> 0;
                        });
    }

    @ParameterizedTest(name = "sort=\"{0}\" is rejected as a client error, not a 500")
    @ValueSource(strings = {"password", "passwordHash", "name;drop table products", "name,asc;select 1", "id) desc, (select 1", "../../x",
            "createdAt,sideways", "status'--"})
    void sortParameter_unknownOrMalicious_isRejectedWith400(String sort) {
        for (String uri : List.of("/api/v1/products?sort={s}", "/api/v1/products/search?q=a&sort={s}")) {
            client.get().uri(uri, sort).exchange().expectStatus().isBadRequest();
        }
        assertSchemaAndDataIntact();
    }

    @Test
    void sortParameter_legitimateColumn_works() {
        client.get().uri("/api/v1/products?sort=name,desc").exchange()
                .expectStatus().isOk()
                .expectBody().jsonPath("$.content[0].name").isEqualTo("Wireless Mouse");
    }

    @Test
    void pageSize_aboveMaximum_isRejectedNotDumped() {
        for (int i = 0; i < 30; i++) {
            jdbc.update("INSERT INTO products (sku, name, price, stock_quantity, status) VALUES (?, ?, 1.00, 1, 'ACTIVE')", "BULK-" + i, "Bulk " + i);
        }
        // above the maximum page size of 100 the request is refused (400) instead of dumping the collection
        client.get().uri("/api/v1/products?size=100000000").exchange().expectStatus().isBadRequest();
        client.get().uri("/api/v1/products/search?q=Bulk&size=2147483647").exchange().expectStatus().isBadRequest();
        client.get().uri("/api/v1/products?size=101").exchange().expectStatus().isBadRequest();
        client.get().uri("/api/v1/products?size=100").exchange()
                .expectStatus().isOk()
                .expectBody().jsonPath("$.size").isEqualTo(100);
    }

    @ParameterizedTest(name = "paging {0} is a 4xx or a safe default, never a 500")
    @ValueSource(strings = {"page=-1", "size=-1", "size=0", "page=abc", "size=1.5", "page=99999999999999999999"})
    void pagingParameters_garbage_neverCauseServerErrors(String query) {
        client.get().uri("/api/v1/products?" + query).exchange()
                .expectStatus().value(s -> assertThat(s).as(query).isLessThan(500));
    }

    @Test
    void pagingParameters_overflowingOffset_isNot500() {
        client.get().uri("/api/v1/products?page=2147483647&size=2000").exchange()
                .expectStatus().value(s -> assertThat(s).isLessThan(500));
    }

    @Test
    void idPathVariable_matrixParamsAndHexAreHarmlessAliases_notInjection() {
        // ";x" is stripped as a matrix parameter and "0x1" parsed as hex 1: both resolve to product 1, nothing is executed.
        client.get().uri("/api/v1/products/1;DROP TABLE products").exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.sku").isEqualTo("SKU-A");
        client.get().uri("/api/v1/products/0x1").exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.sku").isEqualTo("SKU-A");
        assertSchemaAndDataIntact();
    }

    @Test
    void searchQuery_veryLongInput_isHandledWithoutServerError() {
        String huge = "a".repeat(6000);
        client.get().uri("/api/v1/products/search?q=" + huge).exchange()
                .expectStatus().value(s -> assertThat(s).isLessThan(500));
    }

    @Test
    void errorBodies_neverContainSqlFragments() {
        String body = new String(client.get().uri("/api/v1/products/sku/{sku}", "' OR 1=1 --").exchange()
                .expectStatus().isNotFound().expectBody().returnResult().getResponseBody());
        assertThat(body).doesNotContain("SELECT").doesNotContain("select ").doesNotContain("org.postgresql")
                .doesNotContain("org.hibernate").doesNotContain("PSQLException").doesNotContain("syntax error");
    }

    @Test
    void textFields_withNulCharacter_are400() {
        client.post().uri("/api/v1/products").header("X-User-Role", "ADMIN").contentType(MediaType.APPLICATION_JSON)
                .body(productBody("NUL-1", "bad\u0000name", "5.00", 1))
                .exchange().expectStatus().isBadRequest();
        assertThat(countProducts()).isEqualTo(4);
    }
}
