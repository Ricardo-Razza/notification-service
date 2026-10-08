package dev.ricardo.notification.api.admin;

import dev.ricardo.notification.application.ApplicationService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ApplicationAdminController.class)
@TestPropertySource(properties = "app.admin.token=")
class ApplicationAdminDisabledTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private ApplicationService applicationService;

    @Test
    @DisplayName("Quando ADMIN_TOKEN não estiver definido, endpoints admin devem responder 404 ProblemDetail")
    void shouldReturnServiceUnavailableWhenAdminTokenNotConfigured() throws Exception {
        CreateApplicationRequest request = new CreateApplicationRequest("Order Service");

        mockMvc.perform(post("/api/v1/admin/applications")
                        .header("X-Admin-Token", "any-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.type", org.hamcrest.Matchers.is("urn:problem-type:not-found")))
                .andExpect(jsonPath("$.title", org.hamcrest.Matchers.is("Resource Not Found")))
                .andExpect(jsonPath("$.detail", org.hamcrest.Matchers.is("Resource not found")));
    }
}
