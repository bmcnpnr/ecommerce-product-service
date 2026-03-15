package com.ecommerce.product.service;

import com.ecommerce.product.dto.*;
import com.ecommerce.product.exception.*;
import com.ecommerce.product.model.Category;
import com.ecommerce.product.model.Product;
import com.ecommerce.product.model.ProductStatus;
import com.ecommerce.product.repository.CategoryRepository;
import com.ecommerce.product.repository.ProductRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class ProductService {

    private static final Logger log = LoggerFactory.getLogger(ProductService.class);

    private final ProductRepository productRepository;
    private final CategoryRepository categoryRepository;

    @Transactional
    public ProductDTO createProduct(CreateProductRequest request) {
        if (productRepository.existsBySku(request.getSku())) {
            throw new ProductAlreadyExistsException("Product with SKU already exists: " + request.getSku());
        }
        if (request.getCategoryId() != null) {
            categoryRepository.findById(request.getCategoryId())
                    .orElseThrow(() -> new CategoryNotFoundException("Category not found: " + request.getCategoryId()));
        }
        Product product = Product.builder()
                .sku(request.getSku())
                .name(request.getName())
                .description(request.getDescription())
                .price(request.getPrice())
                .stockQuantity(request.getStockQuantity())
                .brand(request.getBrand())
                .categoryId(request.getCategoryId())
                .status(ProductStatus.ACTIVE)
                .build();
        Product saved = productRepository.save(product);
        log.info("Product created: SKU={}", saved.getSku());
        return toDTO(saved);
    }

    @Transactional(readOnly = true)
    public ProductDTO getProductById(Long id) {
        return toDTO(productRepository.findById(id)
                .orElseThrow(() -> new ProductNotFoundException("Product not found with id: " + id)));
    }

    @Transactional(readOnly = true)
    public ProductDTO getProductBySku(String sku) {
        return toDTO(productRepository.findBySku(sku)
                .orElseThrow(() -> new ProductNotFoundException("Product not found with SKU: " + sku)));
    }

    @Transactional(readOnly = true)
    public Page<ProductDTO> getAllProducts(Pageable pageable) {
        return productRepository.findByStatus(ProductStatus.ACTIVE, pageable).map(this::toDTO);
    }

    @Transactional(readOnly = true)
    public Page<ProductDTO> searchProducts(String query, Pageable pageable) {
        return productRepository.findByNameContainingIgnoreCaseAndStatus(query, ProductStatus.ACTIVE, pageable)
                .map(this::toDTO);
    }

    @Transactional(readOnly = true)
    public List<ProductDTO> getProductsByCategory(Long categoryId) {
        categoryRepository.findById(categoryId)
                .orElseThrow(() -> new CategoryNotFoundException("Category not found: " + categoryId));
        return productRepository.findByCategoryId(categoryId).stream()
                .filter(p -> p.getStatus() == ProductStatus.ACTIVE)
                .map(this::toDTO)
                .collect(Collectors.toList());
    }

    @Transactional
    public ProductDTO updateProduct(Long id, UpdateProductRequest request) {
        Product product = productRepository.findById(id)
                .orElseThrow(() -> new ProductNotFoundException("Product not found with id: " + id));

        if (request.getName() != null) product.setName(request.getName());
        if (request.getDescription() != null) product.setDescription(request.getDescription());
        if (request.getPrice() != null) product.setPrice(request.getPrice());
        if (request.getBrand() != null) product.setBrand(request.getBrand());
        if (request.getCategoryId() != null) product.setCategoryId(request.getCategoryId());
        if (request.getStatus() != null) {
            product.setStatus(ProductStatus.valueOf(request.getStatus()));
        }
        product.setUpdatedAt(LocalDateTime.now());
        return toDTO(productRepository.save(product));
    }

    @Transactional
    public ProductDTO updateStock(Long id, Integer delta) {
        Product product = productRepository.findById(id)
                .orElseThrow(() -> new ProductNotFoundException("Product not found with id: " + id));

        int newQuantity = product.getStockQuantity() + delta;
        if (newQuantity < 0) {
            throw new InsufficientStockException(
                    "Insufficient stock for product " + product.getSku() + ". Available: " + product.getStockQuantity() + ", Requested: " + (-delta));
        }
        product.setStockQuantity(newQuantity);
        if (newQuantity == 0) {
            product.setStatus(ProductStatus.OUT_OF_STOCK);
        } else if (product.getStatus() == ProductStatus.OUT_OF_STOCK) {
            product.setStatus(ProductStatus.ACTIVE);
        }
        product.setUpdatedAt(LocalDateTime.now());
        log.info("Stock updated for product {}: {} -> {}", product.getSku(), product.getStockQuantity() - delta, newQuantity);
        return toDTO(productRepository.save(product));
    }

    @Transactional
    public void deleteProduct(Long id) {
        Product product = productRepository.findById(id)
                .orElseThrow(() -> new ProductNotFoundException("Product not found with id: " + id));
        product.setStatus(ProductStatus.INACTIVE);
        product.setUpdatedAt(LocalDateTime.now());
        productRepository.save(product);
        log.info("Product soft-deleted: {}", product.getSku());
    }

    @Transactional
    public CategoryDTO createCategory(CreateCategoryRequest request) {
        if (categoryRepository.existsByName(request.getName())) {
            throw new ProductAlreadyExistsException("Category already exists: " + request.getName());
        }
        Category category = Category.builder()
                .name(request.getName())
                .description(request.getDescription())
                .parentCategoryId(request.getParentCategoryId())
                .build();
        return toCategoryDTO(categoryRepository.save(category));
    }

    @Transactional(readOnly = true)
    public List<CategoryDTO> getAllCategories() {
        return categoryRepository.findAll().stream().map(this::toCategoryDTO).collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public CategoryDTO getCategoryById(Long id) {
        return toCategoryDTO(categoryRepository.findById(id)
                .orElseThrow(() -> new CategoryNotFoundException("Category not found: " + id)));
    }

    private ProductDTO toDTO(Product p) {
        return ProductDTO.builder()
                .id(p.getId()).sku(p.getSku()).name(p.getName()).description(p.getDescription())
                .price(p.getPrice()).stockQuantity(p.getStockQuantity()).brand(p.getBrand())
                .categoryId(p.getCategoryId()).status(p.getStatus().name())
                .createdAt(p.getCreatedAt()).updatedAt(p.getUpdatedAt())
                .build();
    }

    private CategoryDTO toCategoryDTO(Category c) {
        return CategoryDTO.builder()
                .id(c.getId()).name(c.getName()).description(c.getDescription())
                .parentCategoryId(c.getParentCategoryId()).createdAt(c.getCreatedAt())
                .build();
    }
}
