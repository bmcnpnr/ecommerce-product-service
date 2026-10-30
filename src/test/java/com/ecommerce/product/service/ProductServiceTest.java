package com.ecommerce.product.service;

import com.ecommerce.product.dto.*;
import com.ecommerce.product.exception.*;
import com.ecommerce.product.model.*;
import com.ecommerce.product.repository.CategoryRepository;
import com.ecommerce.product.repository.ProductRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ProductServiceTest {

    @Mock
    private ProductRepository productRepository;
    @Mock
    private CategoryRepository categoryRepository;

    @InjectMocks
    private ProductService productService;

    private Product testProduct;

    @BeforeEach
    void setUp() {
        testProduct = Product.builder()
                .id(1L).sku("SKU-001").name("Test Product")
                .price(new BigDecimal("29.99")).stockQuantity(100)
                .status(ProductStatus.ACTIVE)
                .build();
    }

    @Test
    void createProduct_success() {
        CreateProductRequest request = CreateProductRequest.builder()
                .sku("NEW-SKU").name("New Product").price(new BigDecimal("9.99")).stockQuantity(50).build();

        when(productRepository.existsBySku("NEW-SKU")).thenReturn(false);
        when(productRepository.save(any())).thenAnswer(inv -> {
            Product p = inv.getArgument(0);
            p.setId(2L);
            return p;
        });

        ProductDTO result = productService.createProduct(request);

        assertThat(result.getSku()).isEqualTo("NEW-SKU");
        assertThat(result.getName()).isEqualTo("New Product");
    }

    @Test
    void createProduct_duplicateSku_throwsException() {
        CreateProductRequest request = CreateProductRequest.builder()
                .sku("SKU-001").name("Dup").price(BigDecimal.ONE).stockQuantity(1).build();
        when(productRepository.existsBySku("SKU-001")).thenReturn(true);

        assertThatThrownBy(() -> productService.createProduct(request))
                .isInstanceOf(ProductAlreadyExistsException.class);
    }

    @Test
    void getProductById_found() {
        when(productRepository.findById(1L)).thenReturn(Optional.of(testProduct));

        ProductDTO result = productService.getProductById(1L);
        assertThat(result.getSku()).isEqualTo("SKU-001");
    }

    @Test
    void getProductById_notFound_throwsException() {
        when(productRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> productService.getProductById(99L))
                .isInstanceOf(ProductNotFoundException.class);
    }

    @Test
    void updateStock_success() {
        when(productRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(testProduct));
        when(productRepository.save(any())).thenReturn(testProduct);

        ProductDTO result = productService.updateStock(1L, -10);
        assertThat(testProduct.getStockQuantity()).isEqualTo(90);
    }

    @Test
    void updateStock_insufficientStock_throwsException() {
        when(productRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(testProduct));

        assertThatThrownBy(() -> productService.updateStock(1L, -200))
                .isInstanceOf(InsufficientStockException.class)
                .hasMessageContaining("Insufficient stock");
    }

    @Test
    void updateStock_toZero_setsOutOfStock() {
        when(productRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(testProduct));
        when(productRepository.save(any())).thenReturn(testProduct);

        productService.updateStock(1L, -100);
        assertThat(testProduct.getStatus()).isEqualTo(ProductStatus.OUT_OF_STOCK);
    }

    @Test
    void deleteProduct_softDelete() {
        when(productRepository.findById(1L)).thenReturn(Optional.of(testProduct));
        when(productRepository.save(any())).thenReturn(testProduct);

        productService.deleteProduct(1L);
        assertThat(testProduct.getStatus()).isEqualTo(ProductStatus.INACTIVE);
    }

    // ───────────────────────── create ─────────────────────────

    @Test
    void createProduct_withExistingCategory_mapsAllFields() {
        CreateProductRequest request = CreateProductRequest.builder()
                .sku("CAT-SKU").name("In Category").description("desc").price(new BigDecimal("5.50"))
                .stockQuantity(3).brand("Acme").categoryId(10L).build();
        when(productRepository.existsBySku("CAT-SKU")).thenReturn(false);
        when(categoryRepository.findById(10L)).thenReturn(Optional.of(Category.builder().id(10L).name("C").build()));
        when(productRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        ProductDTO result = productService.createProduct(request);

        assertThat(result.getCategoryId()).isEqualTo(10L);
        assertThat(result.getBrand()).isEqualTo("Acme");
        assertThat(result.getDescription()).isEqualTo("desc");
        assertThat(result.getPrice()).isEqualByComparingTo("5.50");
        assertThat(result.getStockQuantity()).isEqualTo(3);
        assertThat(result.getStatus()).isEqualTo("ACTIVE");
        assertThat(result.getCreatedAt()).isNotNull();
    }

    @Test
    void createProduct_unknownCategory_throwsAndDoesNotSave() {
        CreateProductRequest request = CreateProductRequest.builder()
                .sku("X").name("X").price(BigDecimal.ONE).stockQuantity(1).categoryId(404L).build();
        when(productRepository.existsBySku("X")).thenReturn(false);
        when(categoryRepository.findById(404L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> productService.createProduct(request))
                .isInstanceOf(CategoryNotFoundException.class)
                .hasMessage("Category not found: 404");
        verify(productRepository, never()).save(any());
    }

    @Test
    void createProduct_duplicateSku_doesNotLookUpCategoryOrSave() {
        CreateProductRequest request = CreateProductRequest.builder()
                .sku("SKU-001").name("Dup").price(BigDecimal.ONE).stockQuantity(1).categoryId(10L).build();
        when(productRepository.existsBySku("SKU-001")).thenReturn(true);

        assertThatThrownBy(() -> productService.createProduct(request))
                .hasMessage("Product with SKU already exists: SKU-001");
        verifyNoInteractions(categoryRepository);
        verify(productRepository, never()).save(any());
    }

    // ───────────────────────── reads ─────────────────────────

    @Test
    void getProductBySku_found() {
        when(productRepository.findBySku("SKU-001")).thenReturn(Optional.of(testProduct));

        assertThat(productService.getProductBySku("SKU-001").getId()).isEqualTo(1L);
    }

    @Test
    void getProductBySku_notFound_throws() {
        when(productRepository.findBySku("nope")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> productService.getProductBySku("nope"))
                .isInstanceOf(ProductNotFoundException.class)
                .hasMessage("Product not found with SKU: nope");
    }

    @Test
    void getAllProducts_onlyActive_andPassesPageableThrough() {
        Pageable pageable = PageRequest.of(2, 5);
        when(productRepository.findByStatus(ProductStatus.ACTIVE, pageable))
                .thenReturn(new PageImpl<>(List.of(testProduct), pageable, 11));

        Page<ProductDTO> page = productService.getAllProducts(pageable);

        assertThat(page.getTotalElements()).isEqualTo(11);
        assertThat(page.getNumber()).isEqualTo(2);
        assertThat(page.getContent()).extracting(ProductDTO::getSku).containsExactly("SKU-001");
    }

    @Test
    void searchProducts_searchesActiveOnly() {
        Pageable pageable = PageRequest.of(0, 20);
        when(productRepository.findByNameContainingIgnoreCaseAndStatus("mouse", ProductStatus.ACTIVE, pageable))
                .thenReturn(new PageImpl<>(List.of(testProduct), pageable, 1));

        Page<ProductDTO> page = productService.searchProducts("mouse", pageable);

        assertThat(page.getContent()).hasSize(1);
        verify(productRepository).findByNameContainingIgnoreCaseAndStatus("mouse", ProductStatus.ACTIVE, pageable);
    }

    @Test
    void searchProducts_noMatches_returnsEmptyPage() {
        Pageable pageable = PageRequest.of(0, 20);
        when(productRepository.findByNameContainingIgnoreCaseAndStatus("zzz", ProductStatus.ACTIVE, pageable))
                .thenReturn(Page.empty(pageable));

        assertThat(productService.searchProducts("zzz", pageable).getTotalElements()).isZero();
    }

    @Test
    void getProductsByCategory_asksTheDatabaseForOneActivePage_neverLoadsTheWholeCategory() {
        when(categoryRepository.findById(10L)).thenReturn(Optional.of(Category.builder().id(10L).name("C").build()));
        Pageable page = PageRequest.of(0, 100);
        when(productRepository.findByCategoryIdAndStatus(10L, ProductStatus.ACTIVE, page)).thenReturn(List.of(testProduct));

        assertThat(productService.getProductsByCategory(10L, page)).extracting(ProductDTO::getSku).containsExactly("SKU-001");
        verify(productRepository, never()).findByCategoryId(anyLong());
    }

    @Test
    void getProductsByCategory_unknownCategory_throws() {
        when(categoryRepository.findById(10L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> productService.getProductsByCategory(10L, PageRequest.of(0, 100)))
                .isInstanceOf(CategoryNotFoundException.class);
        verifyNoInteractions(productRepository);
    }

    // ───────────────────────── update ─────────────────────────

    @Test
    void updateProduct_appliesOnlyNonNullFields() {
        testProduct.setDescription("keep me");
        testProduct.setBrand("KeepBrand");
        when(productRepository.findById(1L)).thenReturn(Optional.of(testProduct));
        when(productRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        ProductDTO result = productService.updateProduct(1L,
                UpdateProductRequest.builder().name("Renamed").price(new BigDecimal("12.00")).build());

        assertThat(result.getName()).isEqualTo("Renamed");
        assertThat(result.getPrice()).isEqualByComparingTo("12.00");
        assertThat(result.getDescription()).isEqualTo("keep me");
        assertThat(result.getBrand()).isEqualTo("KeepBrand");
        assertThat(result.getStatus()).isEqualTo("ACTIVE");
        assertThat(result.getUpdatedAt()).isNotNull();
    }

    @Test
    void updateProduct_allFields() {
        when(categoryRepository.findById(20L)).thenReturn(Optional.of(Category.builder().id(20L).name("C").build()));
        when(productRepository.findById(1L)).thenReturn(Optional.of(testProduct));
        when(productRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        ProductDTO result = productService.updateProduct(1L, UpdateProductRequest.builder()
                .name("N").description("D").price(new BigDecimal("1.01")).brand("B").categoryId(20L).status("INACTIVE").build());

        assertThat(result).extracting(ProductDTO::getName, ProductDTO::getDescription, ProductDTO::getBrand,
                        ProductDTO::getCategoryId, ProductDTO::getStatus)
                .containsExactly("N", "D", "B", 20L, "INACTIVE");
    }

    @Test
    void updateProduct_statusChange_toOutOfStock() {
        when(productRepository.findById(1L)).thenReturn(Optional.of(testProduct));
        when(productRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        productService.updateProduct(1L, UpdateProductRequest.builder().status("OUT_OF_STOCK").build());

        assertThat(testProduct.getStatus()).isEqualTo(ProductStatus.OUT_OF_STOCK);
    }

    @Test
    void updateProduct_unknownStatus_throwsIllegalArgument_andDoesNotSave() {
        // InvalidRequestException (an IllegalArgumentException) is mapped to 400
        // (see ProductApiIT#updateProduct_unknownStatus_is400).
        when(productRepository.findById(1L)).thenReturn(Optional.of(testProduct));

        assertThatThrownBy(() -> productService.updateProduct(1L, UpdateProductRequest.builder().status("bogus").build()))
                .isInstanceOf(IllegalArgumentException.class);
        verify(productRepository, never()).save(any());
    }

    @Test
    void updateProduct_notFound_throws() {
        when(productRepository.findById(9L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> productService.updateProduct(9L, UpdateProductRequest.builder().name("x").build()))
                .isInstanceOf(ProductNotFoundException.class);
    }

    // ───────────────────────── stock ─────────────────────────

    @Test
    void updateStock_increment_keepsActive() {
        when(productRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(testProduct));
        when(productRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        ProductDTO result = productService.updateStock(1L, 25);

        assertThat(result.getStockQuantity()).isEqualTo(125);
        assertThat(result.getStatus()).isEqualTo("ACTIVE");
        assertThat(result.getUpdatedAt()).isNotNull();
    }

    @Test
    void updateStock_restockFromOutOfStock_reactivates() {
        testProduct.setStockQuantity(0);
        testProduct.setStatus(ProductStatus.OUT_OF_STOCK);
        when(productRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(testProduct));
        when(productRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        ProductDTO result = productService.updateStock(1L, 4);

        assertThat(result.getStockQuantity()).isEqualTo(4);
        assertThat(result.getStatus()).isEqualTo("ACTIVE");
    }

    @Test
    void updateStock_zeroDeltaOnEmptyStock_staysOutOfStock() {
        testProduct.setStockQuantity(0);
        testProduct.setStatus(ProductStatus.OUT_OF_STOCK);
        when(productRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(testProduct));
        when(productRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        assertThat(productService.updateStock(1L, 0).getStatus()).isEqualTo("OUT_OF_STOCK");
    }

    @Test
    void updateStock_restockOfInactiveProduct_staysInactive() {
        testProduct.setStatus(ProductStatus.INACTIVE);
        when(productRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(testProduct));
        when(productRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        assertThat(productService.updateStock(1L, 10).getStatus()).isEqualTo("INACTIVE");
    }

    @Test
    void updateStock_drainingInactiveProduct_staysInactive() {
        testProduct.setStatus(ProductStatus.INACTIVE);
        when(productRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(testProduct));
        when(productRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        productService.updateStock(1L, -100);
        productService.updateStock(1L, 5);

        assertThat(testProduct.getStatus()).isEqualTo(ProductStatus.INACTIVE);
    }

    @Test
    void updateStock_insufficient_reportsAvailableAndRequested_andDoesNotSave() {
        testProduct.setStockQuantity(3);
        when(productRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(testProduct));

        assertThatThrownBy(() -> productService.updateStock(1L, -4))
                .isInstanceOf(InsufficientStockException.class)
                .hasMessage("Insufficient stock for product SKU-001. Available: 3, Requested: 4");
        assertThat(testProduct.getStockQuantity()).isEqualTo(3);
        verify(productRepository, never()).save(any());
    }

    @Test
    void updateStock_productNotFound_throws() {
        when(productRepository.findByIdForUpdate(9L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> productService.updateStock(9L, -1))
                .isInstanceOf(ProductNotFoundException.class)
                .hasMessage("Product not found with id: 9");
    }

    @Test
    void updateStock_restockBeyondIntRange_isRejectedAndNothingIsWritten() {
        testProduct.setStockQuantity(Integer.MAX_VALUE - 1);
        when(productRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(testProduct));

        // MAX-1 + 5 wraps to a negative int: it must not be mistaken for "insufficient stock" nor stored.
        assertThatThrownBy(() -> productService.updateStock(1L, 5))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessage("Stock quantity out of range");
        assertThat(testProduct.getStockQuantity()).isEqualTo(Integer.MAX_VALUE - 1);
        verify(productRepository, never()).save(any());
    }

    @Test
    void updateStock_exactlyIntegerMaxIsStillAllowed() {
        testProduct.setStockQuantity(Integer.MAX_VALUE - 1);
        when(productRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(testProduct));
        when(productRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        assertThat(productService.updateStock(1L, 1).getStockQuantity()).isEqualTo(Integer.MAX_VALUE);
    }

    @Test
    void updateStock_minimumIntegerDelta_reportsTheRealRequestedQuantity() {
        when(productRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(testProduct));

        // -Integer.MIN_VALUE overflows int; the message must still say 2147483648, not a negative number.
        assertThatThrownBy(() -> productService.updateStock(1L, Integer.MIN_VALUE))
                .isInstanceOf(InsufficientStockException.class)
                .hasMessage("Insufficient stock for product SKU-001. Available: 100, Requested: 2147483648");
    }

    @Test
    void theStockLogLine_cannotBeForgedThroughAnAdminChosenSku() {
        ch.qos.logback.classic.Logger serviceLogger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(ProductService.class);
        ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> logs = new ch.qos.logback.core.read.ListAppender<>();
        logs.start();
        serviceLogger.addAppender(logs);
        try {
            testProduct.setSku("SKU-1\n2026-10-30 00:00:00 ERROR forged line");
            when(productRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(testProduct));
            when(productRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            productService.updateStock(1L, -1);

            assertThat(logs.list).hasSize(1);
            assertThat(logs.list.get(0).getFormattedMessage()).doesNotContain("\n").contains("SKU-1_2026-10-30");
        } finally {
            serviceLogger.detachAppender(logs);
        }
    }

    @Test
    void updateStock_locksTheRowBeforeReadingIt_andNeverReadsItWithoutTheLock() {
        when(productRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(testProduct));
        when(productRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        productService.updateStock(1L, -1);

        verify(productRepository).findByIdForUpdate(1L);
        verify(productRepository, never()).findById(any());
    }

    @Test
    void deleteProduct_notFound_throws() {
        when(productRepository.findById(9L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> productService.deleteProduct(9L)).isInstanceOf(ProductNotFoundException.class);
        verify(productRepository, never()).save(any());
    }

    // ───────────────────────── categories ─────────────────────────

    @Test
    void createCategory_success() {
        when(categoryRepository.findById(10L)).thenReturn(Optional.of(Category.builder().id(10L).name("Parent").build()));
        when(categoryRepository.existsByName("Mice")).thenReturn(false);
        when(categoryRepository.save(any())).thenAnswer(inv -> {
            Category c = inv.getArgument(0);
            c.setId(11L);
            return c;
        });

        CategoryDTO result = productService.createCategory(
                CreateCategoryRequest.builder().name("Mice").description("Pointing").parentCategoryId(10L).build());

        assertThat(result.getId()).isEqualTo(11L);
        assertThat(result.getParentCategoryId()).isEqualTo(10L);
        assertThat(result.getDescription()).isEqualTo("Pointing");
        assertThat(result.getCreatedAt()).isNotNull();
    }

    @Test
    void createCategory_duplicateName_throwsConflict() {
        when(categoryRepository.existsByName("Mice")).thenReturn(true);

        assertThatThrownBy(() -> productService.createCategory(CreateCategoryRequest.builder().name("Mice").build()))
                .isInstanceOf(ProductAlreadyExistsException.class)
                .hasMessage("Category already exists: Mice");
        verify(categoryRepository, never()).save(any());
    }

    @Test
    void getAllCategories_mapsEveryCategory() {
        when(categoryRepository.findAll(any(Pageable.class))).thenReturn(new PageImpl<>(List.of(
                Category.builder().id(1L).name("A").build(),
                Category.builder().id(2L).name("B").parentCategoryId(1L).build())));

        assertThat(productService.getAllCategories())
                .extracting(CategoryDTO::getName, CategoryDTO::getParentCategoryId)
                .containsExactly(tuple("A", null), tuple("B", 1L));
    }

    @Test
    void getAllCategories_isBoundedByDesign_orderedById() {
        when(categoryRepository.findAll(any(Pageable.class))).thenReturn(new PageImpl<>(List.of()));

        productService.getAllCategories();

        verify(categoryRepository).findAll(PageRequest.of(0, ProductService.MAX_CATEGORIES, org.springframework.data.domain.Sort.by("id")));
        verify(categoryRepository, never()).findAll();
    }

    @Test
    void getCategoryById_foundAndNotFound() {
        when(categoryRepository.findById(1L)).thenReturn(Optional.of(Category.builder().id(1L).name("A").build()));
        when(categoryRepository.findById(2L)).thenReturn(Optional.empty());

        assertThat(productService.getCategoryById(1L).getName()).isEqualTo("A");
        assertThatThrownBy(() -> productService.getCategoryById(2L))
                .isInstanceOf(CategoryNotFoundException.class)
                .hasMessage("Category not found: 2");
    }
}
