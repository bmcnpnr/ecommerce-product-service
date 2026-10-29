package com.ecommerce.product.filter;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

class ActuatorRegistryFilterTest {

    private final ActuatorRegistryFilter filter = new ActuatorRegistryFilter();

    private int call(String path, String remote) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", path);
        request.setRemoteAddr(remote);
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, (req, res) -> ((MockHttpServletResponse) res).setStatus(204));
        return response.getStatus();
    }

    @Test
    void serviceRegistry_isLoopbackOnly() throws Exception {
        assertThat(call("/actuator/serviceregistry", "127.0.0.1")).isEqualTo(204);
        assertThat(call("/actuator/serviceregistry", "0:0:0:0:0:0:0:1")).isEqualTo(204);
        assertThat(call("/actuator/serviceregistry", "10.1.2.3")).isEqualTo(403);
        assertThat(call("/actuator/serviceregistry", "evil.example.com")).isEqualTo(403);
    }

    @Test
    void otherActuatorEndpoints_areNotRestricted() throws Exception {
        assertThat(call("/actuator/health", "10.1.2.3")).isEqualTo(204);
    }
}
