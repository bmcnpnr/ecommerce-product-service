package com.ecommerce.product.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.junit.jupiter.MockitoExtension;

import static com.ecommerce.product.service.ProductService.HELLO_FROM_PRODUCT_SERVICE;
import static org.junit.jupiter.api.Assertions.assertEquals;

@ExtendWith(MockitoExtension.class)
public class ProductServiceTest {

    @InjectMocks
    private ProductService productService;

    @Test
    void testGetHelloMessage() {
        // when
        var result = productService.getHelloMessage();

        // then
        assertEquals(HELLO_FROM_PRODUCT_SERVICE, result);
    }
}
