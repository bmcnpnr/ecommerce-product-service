package com.ecommerce.product.repository;

import com.ecommerce.product.model.Product;
import com.ecommerce.product.model.ProductStatus;
import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ProductRepository extends JpaRepository<Product, Long> {
    /**
     * Loads the row under {@code SELECT ... FOR UPDATE} so concurrent stock updates serialize instead of racing
     * read-modify-write. The wait is bounded: on PostgreSQL Hibernate sets {@code lock_timeout} for the
     * transaction, so a stuck holder costs a waiting buyer at most 5 s (then a lock-timeout exception, answered
     * 503) instead of pinning a pooled connection per queued buyer until the pool is gone.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "5000"))
    @Query("select p from Product p where p.id = :id")
    Optional<Product> findByIdForUpdate(@Param("id") Long id);

    Optional<Product> findBySku(String sku);
    List<Product> findByCategoryId(Long categoryId);
    List<Product> findByStatus(ProductStatus status);
    Page<Product> findByNameContainingIgnoreCaseAndStatus(String name, ProductStatus status, Pageable pageable);
    Page<Product> findByStatus(ProductStatus status, Pageable pageable);
    /** One page of a category's products; the status filter runs in SQL so a big category never loads whole. */
    List<Product> findByCategoryIdAndStatus(Long categoryId, ProductStatus status, Pageable pageable);
    boolean existsBySku(String sku);
}
