package com.ecommerce.product.security;

import java.lang.annotation.*;

/** Endpoint class "admin": {@code X-User-Role} must be exactly {@code ADMIN} (401 without identity, else 403). */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface AdminOnly {
}
