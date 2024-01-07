package com.ecommerce.product.service;

import org.springframework.stereotype.Service;

@Service
public class ProductService {

    public static final String HELLO_FROM_PRODUCT_SERVICE = "Hello from Product Service!";

    public String getHelloMessage() {
        return HELLO_FROM_PRODUCT_SERVICE;
    }
}
