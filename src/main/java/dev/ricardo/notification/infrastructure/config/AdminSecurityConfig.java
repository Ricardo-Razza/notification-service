package dev.ricardo.notification.infrastructure.config;

import dev.ricardo.notification.infrastructure.persistence.AdminUserRepository;
import dev.ricardo.notification.security.AdminSessionValidationFilter;
import jakarta.servlet.http.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import org.springframework.core.annotation.Order;
import org.springframework.http.*;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.security.web.context.*;
import org.springframework.security.web.csrf.*;
import tools.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.function.Supplier;

@Configuration
public class AdminSecurityConfig {
    @Bean PasswordEncoder passwordEncoder() {return new BCryptPasswordEncoder();}
    @Bean UserDetailsService disabledBasicUsers() {return username->{throw new UsernameNotFoundException("Use admin session login");};}
    @Bean SecurityContextRepository adminContextRepository() {return new HttpSessionSecurityContextRepository();}
    @Bean @Order(1)
    SecurityFilterChain adminSecurity(HttpSecurity http,AdminUserRepository users,ObjectMapper mapper,
            @Value("${app.admin.cookie-secure:false}") boolean secure,SecurityContextRepository repository) throws Exception {
        var csrf=CookieCsrfTokenRepository.withHttpOnlyFalse();csrf.setCookieCustomizer(cookie->cookie.path("/").sameSite("Strict").secure(secure));
        http.securityMatcher("/api/admin/**")
                .securityContext(c->c.securityContextRepository(repository))
                .sessionManagement(s->s.sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED))
                .csrf(c->c.csrfTokenRepository(csrf).csrfTokenRequestHandler(new SpaHandler()))
                .requestCache(c->c.disable()).formLogin(c->c.disable()).httpBasic(c->c.disable()).logout(c->c.disable())
                .authorizeHttpRequests(a->a.requestMatchers("/api/admin/auth/login","/api/admin/auth/me").permitAll()
                        .requestMatchers("/api/admin/auth/logout").authenticated()
                        .requestMatchers("/api/admin/users/**","/api/admin/audit-log/**").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.GET,"/api/admin/**").hasAnyRole("ADMIN","VIEWER")
                        .requestMatchers(HttpMethod.HEAD,"/api/admin/**").hasAnyRole("ADMIN","VIEWER")
                        .anyRequest().hasRole("ADMIN"))
                .exceptionHandling(e->e.authenticationEntryPoint((req,res,error)->problem(mapper,res,401,"Authentication required"))
                        .accessDeniedHandler((req,res,error)->problem(mapper,res,403,"Access denied or invalid CSRF token")))
                .addFilterBefore(new AdminSessionValidationFilter(users),AuthorizationFilter.class);
        return http.build();
    }
    @Bean @Order(2)
    SecurityFilterChain systemsSecurity(HttpSecurity http) throws Exception {
        return http.csrf(c->c.disable()).sessionManagement(s->s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .requestCache(c->c.disable()).formLogin(c->c.disable()).httpBasic(c->c.disable()).logout(c->c.disable())
                .authorizeHttpRequests(a->a.anyRequest().permitAll()).build();
    }
    private void problem(ObjectMapper mapper,HttpServletResponse response,int status,String detail) throws IOException {
        response.setStatus(status);response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);response.setHeader("Cache-Control","no-store");
        mapper.writeValue(response.getWriter(),ProblemDetail.forStatusAndDetail(HttpStatus.valueOf(status),detail));
    }
    private static class SpaHandler implements CsrfTokenRequestHandler {
        private final CsrfTokenRequestHandler plain=new CsrfTokenRequestAttributeHandler();
        private final CsrfTokenRequestHandler xor=new XorCsrfTokenRequestAttributeHandler();
        @Override public void handle(HttpServletRequest request,HttpServletResponse response,Supplier<CsrfToken> token) {xor.handle(request,response,token);token.get();}
        @Override public String resolveCsrfTokenValue(HttpServletRequest request,CsrfToken token) {
            return request.getHeader(token.getHeaderName())!=null?plain.resolveCsrfTokenValue(request,token):xor.resolveCsrfTokenValue(request,token);
        }
    }
}
