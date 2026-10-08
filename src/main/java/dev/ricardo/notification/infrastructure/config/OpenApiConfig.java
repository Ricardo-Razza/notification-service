package dev.ricardo.notification.infrastructure.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    public static final String API_KEY_SECURITY_SCHEME = "ApiKeyAuth";
    public static final String ADMIN_TOKEN_SECURITY_SCHEME = "AdminTokenAuth";

    @Bean
    public OpenAPI customOpenAPI(@org.springframework.beans.factory.annotation.Value("${app.api.title:Notification Service API}") String title,
            @org.springframework.beans.factory.annotation.Value("${app.api.description:Transactional notification service}") String description,
            @org.springframework.beans.factory.annotation.Value("${app.api.version:1.0.0}") String version) {
        return new OpenAPI()
                .info(new Info()
                        .title(title)
                        .description(description)
                        .version(version))
                .addSecurityItem(new SecurityRequirement()
                        .addList(API_KEY_SECURITY_SCHEME))
                .components(new Components()
                        .addSecuritySchemes(API_KEY_SECURITY_SCHEME, new SecurityScheme()
                                .type(SecurityScheme.Type.APIKEY)
                                .in(SecurityScheme.In.HEADER)
                                .name("X-API-Key")
                                .description("Application API key"))
                        .addSecuritySchemes(ADMIN_TOKEN_SECURITY_SCHEME, new SecurityScheme()
                                .type(SecurityScheme.Type.APIKEY)
                                .in(SecurityScheme.In.HEADER)
                                .name("X-Admin-Token")
                                .description("Token administrativo para provisionamento de aplicações e métricas"))
                        .addSecuritySchemes("SessionAuth",new SecurityScheme().type(SecurityScheme.Type.APIKEY)
                                .in(SecurityScheme.In.COOKIE).name("JSESSIONID").description("Admin session obtained through login")));
    }

    @Bean
    public org.springdoc.core.models.GroupedOpenApi publicApi() {
        return org.springdoc.core.models.GroupedOpenApi.builder().group("public").pathsToMatch("/api/v1/**")
                .pathsToExclude("/api/v1/admin/**")
                .addOpenApiCustomizer(api->filterPaths(api,"/api/v1/","/api/v1/admin/")).build();
    }

    @Bean
    public org.springdoc.core.models.GroupedOpenApi adminApi() {
        return org.springdoc.core.models.GroupedOpenApi.builder().group("admin").pathsToMatch("/api/admin/**")
                .addOpenApiCustomizer(api->{filterPaths(api,"/api/admin/",null);api.getPaths().forEach((path,item)->item.readOperations().forEach(operation->{
                    operation.setSecurity(path.startsWith("/api/admin/auth/login")||path.startsWith("/api/admin/auth/me")?java.util.List.of():
                            java.util.List.of(new SecurityRequirement().addList("SessionAuth")));
                }));}).build();
    }

    @Bean
    public org.springdoc.core.models.GroupedOpenApi systemAdminApi() {
        return org.springdoc.core.models.GroupedOpenApi.builder().group("system-admin").pathsToMatch("/api/v1/admin/**")
                .addOpenApiCustomizer(api -> api.getPaths().values().forEach(path -> path.readOperations().forEach(operation ->
                        operation.setSecurity(java.util.List.of(new SecurityRequirement().addList(ADMIN_TOKEN_SECURITY_SCHEME)))))).build();
    }

    private void filterPaths(OpenAPI api,String prefix,String excluded) {
        var paths=new io.swagger.v3.oas.models.Paths();
        api.getPaths().forEach((path,item)->{if(path.startsWith(prefix)&&(excluded==null||!path.startsWith(excluded)))paths.addPathItem(path,item);});
        api.setPaths(paths);
    }

    @Bean
    public org.springdoc.core.customizers.OperationCustomizer operationErrors() {
        return (operation, method) -> {
            for (String code : java.util.List.of("400","401","404","409","422","429","503")) {
                if (!operation.getResponses().containsKey(code)) operation.getResponses().addApiResponse(code,
                        new io.swagger.v3.oas.models.responses.ApiResponse().description("ProblemDetail")
                                .content(new io.swagger.v3.oas.models.media.Content().addMediaType("application/problem+json",
                                        new io.swagger.v3.oas.models.media.MediaType().schema(new io.swagger.v3.oas.models.media.Schema<>().type("object")
                                                .addProperty("status",new io.swagger.v3.oas.models.media.IntegerSchema())
                                                .addProperty("title",new io.swagger.v3.oas.models.media.StringSchema())
                                                .addProperty("detail",new io.swagger.v3.oas.models.media.StringSchema())))));
            }
            return operation;
        };
    }
}
