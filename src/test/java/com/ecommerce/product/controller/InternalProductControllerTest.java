package com.ecommerce.product.controller;

import com.ecommerce.product.dto.ProductDTO;
import com.ecommerce.product.service.ProductService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(InternalProductController.class)
class InternalProductControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ProductService productService;

    @Test
    void stock_needsNoIdentity_andReturnsTheProduct() throws Exception {
        when(productService.updateStock(eq(1L), any())).thenReturn(ProductDTO.builder().id(1L).stockQuantity(48).status("ACTIVE").build());

        mockMvc.perform(patch("/internal/v1/products/1/stock").contentType(MediaType.APPLICATION_JSON).content("{\"delta\":-2}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stockQuantity").value(48));
    }

    @Test
    void stock_missingDelta_is400() throws Exception {
        mockMvc.perform(patch("/internal/v1/products/1/stock").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
    }
}
