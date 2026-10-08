package dev.ricardo.notification.api;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.ObjectMapper;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.assertj.core.api.Assertions.assertThat;

class ActuatorSecurityFilterTest {
    @Test
    void metricsRootRequiresAdminToken() throws Exception {
        var filter = new ActuatorSecurityFilter("secret", new ObjectMapper());
        var request = new MockHttpServletRequest("GET", "/actuator/metrics");
        var response = new MockHttpServletResponse();
        var invoked = new AtomicBoolean();
        filter.doFilter(request, response, (req, res) -> invoked.set(true));
        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(invoked).isFalse();
        request = new MockHttpServletRequest("GET", "/actuator/metrics");
        request.addHeader("X-Admin-Token", "secret");
        filter.doFilter(request, new MockHttpServletResponse(), (req, res) -> invoked.set(true));
        assertThat(invoked).isTrue();
    }

    @Test
    void healthIsPublicWithoutAdminToken() throws Exception {
        var invoked = new AtomicBoolean();
        new ActuatorSecurityFilter("", new ObjectMapper()).doFilter(
                new MockHttpServletRequest("GET", "/actuator/health"),
                new MockHttpServletResponse(), (req, res) -> invoked.set(true));
        assertThat(invoked).isTrue();
    }
}
