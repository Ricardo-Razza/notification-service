package dev.ricardo.notification.api;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.*;
import tools.jackson.databind.ObjectMapper;
import org.slf4j.MDC;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.assertj.core.api.Assertions.*;

class RequestFiltersTest {
    @Test void bodyLimitCoversUnknownContentLengthAndDoesNotInvokeChain() throws Exception {
        var request=new MockHttpServletRequest("POST","/api/v1/notifications") {
            @Override public long getContentLengthLong() {return -1;}
        };
        request.setContent(new byte[101]); var response=new MockHttpServletResponse(); var invoked=new AtomicBoolean();
        new RequestBodyLimitFilter(100,new ObjectMapper()).doFilter(request,response,(a,b)->invoked.set(true));
        assertThat(response.getStatus()).isEqualTo(413); assertThat(invoked).isFalse();
    }
    @Test void correlationReturnsIdAndRestoresMdcEvenAfterFailure() {
        MDC.put("other","previous"); var request=new MockHttpServletRequest("GET","/anything");
        request.addHeader("X-Request-Id","request-123"); var response=new MockHttpServletResponse();
        try {
            assertThatThrownBy(() -> new RequestCorrelationFilter().doFilter(request,response,(a,b)->{
                assertThat(MDC.get("requestId")).isEqualTo("request-123"); throw new jakarta.servlet.ServletException("Failure");
            })).isInstanceOf(jakarta.servlet.ServletException.class);
            assertThat(response.getHeader("X-Request-Id")).isEqualTo("request-123");
            assertThat(MDC.get("requestId")).isNull(); assertThat(MDC.get("other")).isEqualTo("previous");
        } finally {MDC.clear();}
    }
    @Test void disabledAdminRejectsAnyRouteBeforePayloadValidation() throws Exception {
        var response=new MockHttpServletResponse(); var invoked=new AtomicBoolean();
        new AdminTokenAuthFilter("",new ObjectMapper()).doFilter(new MockHttpServletRequest("POST","/api/v1/admin/applications"),response,(a,b)->invoked.set(true));
        assertThat(response.getStatus()).isEqualTo(404); assertThat(invoked).isFalse();
    }
}
