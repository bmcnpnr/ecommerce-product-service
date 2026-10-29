package com.ecommerce.product.dto;

import jakarta.validation.Constraint;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.Payload;

import java.lang.annotation.*;

/** Rejects strings containing NUL characters, which PostgreSQL cannot store. */
@Target({ElementType.FIELD, ElementType.PARAMETER})
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = NoNul.Validator.class)
public @interface NoNul {
    String message() default "Must not contain NUL characters";
    Class<?>[] groups() default {};
    Class<? extends Payload>[] payload() default {};

    class Validator implements ConstraintValidator<NoNul, CharSequence> {
        @Override
        public boolean isValid(CharSequence value, ConstraintValidatorContext context) {
            if (value == null) return true;
            for (int i = 0; i < value.length(); i++) {
                if (value.charAt(i) == '\0') return false;
            }
            return true;
        }
    }
}
