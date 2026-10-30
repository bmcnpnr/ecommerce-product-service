package com.ecommerce.product.util;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LogSanitizerTest {

    @Test
    void controlCharactersCannotForgeALogLine() {
        assertThat(LogSanitizer.clean("SKU-1\nERROR fake line\r\tend\u0000")).isEqualTo("SKU-1_ERROR fake line__end_");
    }

    @Test
    void longValuesAreCapped() {
        assertThat(LogSanitizer.clean("x".repeat(1000))).hasSize(256);
    }

    @Test
    void nullAndOrdinaryValues() {
        assertThat(LogSanitizer.clean(null)).isEqualTo("null");
        assertThat(LogSanitizer.clean("SKU-001 é中")).isEqualTo("SKU-001 é中");
    }
}
