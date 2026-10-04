package dev.ricardo.notification.infrastructure.config;

import dev.ricardo.notification.api.ActuatorSecurityFilter;
import dev.ricardo.notification.api.ApiKeyAuthFilter;
import dev.ricardo.notification.domain.ApiKeyService;
import dev.ricardo.notification.infrastructure.persistence.ApplicationRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.ObjectMapper;

@Configuration
public class SecurityFilterConfig {

    @Bean
    public ApiKeyAuthFilter apiKeyAuthFilter(
            ApiKeyService apiKeyService,
            ApplicationRepository applicationRepository,
            ObjectMapper objectMapper
    ) {
        return new ApiKeyAuthFilter(apiKeyService, applicationRepository, objectMapper);
    }

    @Bean
    public FilterRegistrationBean<ApiKeyAuthFilter> apiKeyAuthFilterRegistration(ApiKeyAuthFilter filter) {
        FilterRegistrationBean<ApiKeyAuthFilter> registration = new FilterRegistrationBean<>(filter);
        registration.addUrlPatterns("/api/v1/*");
        registration.setOrder(1);
        return registration;
    }

    @Bean
    public ActuatorSecurityFilter actuatorSecurityFilter(
            @Value("${app.admin.token:}") String adminToken,
            ObjectMapper objectMapper
    ) {
        return new ActuatorSecurityFilter(adminToken, objectMapper);
    }

    @Bean
    public FilterRegistrationBean<ActuatorSecurityFilter> actuatorSecurityFilterRegistration(ActuatorSecurityFilter filter) {
        FilterRegistrationBean<ActuatorSecurityFilter> registration = new FilterRegistrationBean<>(filter);
        registration.addUrlPatterns("/actuator/*");
        registration.setOrder(2);
        return registration;
    }
}
