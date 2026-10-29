package com.ecommerce.product.integration;

import com.ecommerce.product.model.Category;
import com.ecommerce.product.model.Product;
import com.ecommerce.product.model.ProductStatus;
import com.ecommerce.product.repository.CategoryRepository;
import com.ecommerce.product.repository.ProductRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Repositories against PostgreSQL with the production Flyway schema. Each test
 * runs in a transaction that @DataJpaTest rolls back.
 */
@DataJpaTest(properties = {
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.flyway.enabled=true"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class ProductRepositoryIT extends PostgresIntegrationTestSupport {

    @Autowired private ProductRepository productRepository;
    @Autowired private CategoryRepository categoryRepository;
    @Autowired private EntityManager em;
    @Autowired private JdbcTemplate jdbc;

    @BeforeEach
    void cleanTables() {
        // Other IT classes share the container and commit data; this is rolled back with the test.
        jdbc.update("DELETE FROM products");
        jdbc.update("DELETE FROM categories");
    }

    @Test
    void flywayMigrationsWereApplied() {
        List<String> versions = jdbc.queryForList(
                "SELECT version FROM flyway_schema_history WHERE success ORDER BY installed_rank", String.class);
        assertThat(versions).containsExactly("1", "2");
    }

    @Test
    void searchByName_isCaseInsensitive_filtersByStatus_andPages() {
        productRepository.save(product("SKU-K", "Wireless Keyboard", ProductStatus.ACTIVE));
        productRepository.save(product("SKU-M", "Wireless Mouse", ProductStatus.ACTIVE));
        productRepository.save(product("SKU-C", "Wireless Charger", ProductStatus.INACTIVE));
        productRepository.save(product("SKU-H", "USB Hub", ProductStatus.ACTIVE));
        productRepository.flush();

        Page<Product> first = productRepository.findByNameContainingIgnoreCaseAndStatus(
                "wireLESS", ProductStatus.ACTIVE, PageRequest.of(0, 1, Sort.by("name")));
        Page<Product> second = productRepository.findByNameContainingIgnoreCaseAndStatus(
                "wireLESS", ProductStatus.ACTIVE, PageRequest.of(1, 1, Sort.by("name")));

        assertThat(first.getTotalElements()).isEqualTo(2);
        assertThat(first.getTotalPages()).isEqualTo(2);
        assertThat(first.getContent()).extracting(Product::getName).containsExactly("Wireless Keyboard");
        assertThat(second.getContent()).extracting(Product::getName).containsExactly("Wireless Mouse");
    }

    @Test
    void searchByName_treatsLikeWildcardsLiterally() {
        productRepository.save(product("SKU-1", "100% Cotton Shirt", ProductStatus.ACTIVE));
        productRepository.save(product("SKU-2", "Linen_Shirt", ProductStatus.ACTIVE));
        productRepository.save(product("SKU-3", "Wool Shirt", ProductStatus.ACTIVE));
        productRepository.flush();

        assertThat(productRepository.findByNameContainingIgnoreCaseAndStatus("%", ProductStatus.ACTIVE, PageRequest.of(0, 10)))
                .extracting(Product::getName).containsExactly("100% Cotton Shirt");
        assertThat(productRepository.findByNameContainingIgnoreCaseAndStatus("_", ProductStatus.ACTIVE, PageRequest.of(0, 10)))
                .extracting(Product::getName).containsExactly("Linen_Shirt");
    }

    @Test
    void findByStatus_pageAndList() {
        productRepository.save(product("SKU-A", "A", ProductStatus.ACTIVE));
        productRepository.save(product("SKU-B", "B", ProductStatus.ACTIVE));
        productRepository.save(product("SKU-O", "O", ProductStatus.OUT_OF_STOCK));
        productRepository.flush();

        assertThat(productRepository.findByStatus(ProductStatus.ACTIVE, PageRequest.of(0, 10)).getTotalElements()).isEqualTo(2);
        assertThat(productRepository.findByStatus(ProductStatus.OUT_OF_STOCK)).extracting(Product::getSku).containsExactly("SKU-O");
    }

    @Test
    void findBySku_andExistsBySku() {
        productRepository.saveAndFlush(product("SKU-X", "X", ProductStatus.ACTIVE));

        assertThat(productRepository.existsBySku("SKU-X")).isTrue();
        assertThat(productRepository.existsBySku("sku-x")).isFalse();
        assertThat(productRepository.findBySku("SKU-X")).get().extracting(Product::getName).isEqualTo("X");
        assertThat(productRepository.findBySku("missing")).isEmpty();
    }

    @Test
    void findByCategoryId_returnsAllStatuses() {
        Category cat = categoryRepository.saveAndFlush(category("Electronics", null));
        Product p1 = product("SKU-1", "One", ProductStatus.ACTIVE);
        p1.setCategoryId(cat.getId());
        Product p2 = product("SKU-2", "Two", ProductStatus.INACTIVE);
        p2.setCategoryId(cat.getId());
        productRepository.saveAll(List.of(p1, p2, product("SKU-3", "Uncategorised", ProductStatus.ACTIVE)));
        productRepository.flush();

        assertThat(productRepository.findByCategoryId(cat.getId()))
                .extracting(Product::getSku).containsExactlyInAnyOrder("SKU-1", "SKU-2");
    }

    @Test
    void duplicateSku_isRejectedByUniqueConstraint() {
        productRepository.saveAndFlush(product("DUP-1", "First", ProductStatus.ACTIVE));

        assertThatThrownBy(() -> productRepository.saveAndFlush(product("DUP-1", "Second", ProductStatus.ACTIVE)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void duplicateCategoryName_isRejectedByUniqueConstraint() {
        categoryRepository.saveAndFlush(category("Books", null));

        assertThat(categoryRepository.existsByName("Books")).isTrue();
        assertThat(categoryRepository.findByName("Books")).isPresent();
        assertThatThrownBy(() -> categoryRepository.saveAndFlush(category("Books", null)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void productWithUnknownCategory_isRejectedByForeignKey() {
        Product p = product("FK-1", "Orphan", ProductStatus.ACTIVE);
        p.setCategoryId(987654L);

        assertThatThrownBy(() -> productRepository.saveAndFlush(p))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void categoryWithUnknownParent_isRejectedByForeignKey() {
        assertThatThrownBy(() -> categoryRepository.saveAndFlush(category("Orphan", 987654L)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void deletingParentCategory_setsChildParentAndProductCategoryToNull() {
        Category parent = categoryRepository.saveAndFlush(category("Electronics", null));
        Category child = categoryRepository.saveAndFlush(category("Mice", parent.getId()));
        Product p = product("SKU-M", "Mouse", ProductStatus.ACTIVE);
        p.setCategoryId(parent.getId());
        p = productRepository.saveAndFlush(p);

        categoryRepository.delete(parent);
        categoryRepository.flush();
        em.clear();

        assertThat(categoryRepository.findById(child.getId())).get()
                .extracting(Category::getParentCategoryId).isNull();
        assertThat(productRepository.findById(p.getId())).get()
                .extracting(Product::getCategoryId).isNull();
    }

    @Test
    void price_isStoredAsNumeric19_4() {
        Product rounded = product("P-1", "Rounded", ProductStatus.ACTIVE);
        rounded.setPrice(new BigDecimal("1.23456"));
        Product large = product("P-2", "Large", ProductStatus.ACTIVE);
        large.setPrice(new BigDecimal("123456789012345.6789"));
        Product plain = product("P-3", "Plain", ProductStatus.ACTIVE);
        plain.setPrice(new BigDecimal("19.99"));
        productRepository.saveAllAndFlush(List.of(rounded, large, plain));
        em.clear();

        assertThat(productRepository.findBySku("P-1").orElseThrow().getPrice()).isEqualTo(new BigDecimal("1.2346"));
        assertThat(productRepository.findBySku("P-2").orElseThrow().getPrice()).isEqualTo(new BigDecimal("123456789012345.6789"));
        assertThat(productRepository.findBySku("P-3").orElseThrow().getPrice()).isEqualTo(new BigDecimal("19.9900"));
    }

    @Test
    void timestampsAndDefaults_roundTrip() {
        Product saved = productRepository.saveAndFlush(product("TS-1", "Timestamps", ProductStatus.ACTIVE));
        em.clear();

        Product loaded = productRepository.findById(saved.getId()).orElseThrow();
        assertThat(loaded.getCreatedAt()).isNotNull();
        assertThat(loaded.getStatus()).isEqualTo(ProductStatus.ACTIVE);
        assertThat(loaded.getStockQuantity()).isEqualTo(5);
    }

    private static Product product(String sku, String name, ProductStatus status) {
        return Product.builder()
                .sku(sku).name(name).price(new BigDecimal("9.99")).stockQuantity(5).status(status)
                .build();
    }

    private static Category category(String name, Long parentId) {
        return Category.builder().name(name).description(name + " category").parentCategoryId(parentId).build();
    }
}
