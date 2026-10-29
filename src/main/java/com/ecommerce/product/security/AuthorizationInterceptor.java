package com.ecommerce.product.security;

import com.ecommerce.product.exception.ForbiddenException;
import com.ecommerce.product.exception.UnauthorizedException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Enforces {@link AdminOnly} before the handler (and so the service layer) runs. The gateway verifies the
 * JWT and sets the identity headers; the role is compared case-sensitively with no trimming.
 */
@Component
public class AuthorizationInterceptor implements HandlerInterceptor, WebMvcConfigurer {

    public static final String ROLE_HEADER = "X-User-Role";
    public static final String USERNAME_HEADER = "X-Username";

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(this);
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (handler instanceof HandlerMethod method && method.hasMethodAnnotation(AdminOnly.class)) {
            if (!isAdmin(request)) {
                String username = request.getHeader(USERNAME_HEADER);
                String role = request.getHeader(ROLE_HEADER);
                if ((username == null || username.isBlank()) && (role == null || role.isBlank())) {
                    throw new UnauthorizedException("Authentication required");
                }
                throw new ForbiddenException("Access denied");
            }
        }
        return true;
    }

    public static boolean isAdmin(HttpServletRequest request) {
        return "ADMIN".equals(request.getHeader(ROLE_HEADER));
    }
}
