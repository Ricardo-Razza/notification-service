package dev.ricardo.notification.api.admin;

import dev.ricardo.notification.application.*;
import dev.ricardo.notification.domain.*;
import dev.ricardo.notification.security.AdminPrincipal;
import jakarta.servlet.http.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController @RequestMapping("/api/admin/auth") @RequiredArgsConstructor
@io.swagger.v3.oas.annotations.tags.Tag(name="Admin authentication")
public class AdminAuthController {
    private final AdminUserService users;
    private final AdminAuditService audit;
    private final SecurityContextRepository repository;
    @io.swagger.v3.oas.annotations.media.Schema(name="AdminLoginRequest")
    public record Login(@NotBlank @Email @Size(max=320) String email,@NotBlank @Size(max=72) String password) {}
    @GetMapping("/me")
    public AdminPrincipal me() {return principal();}
    @PostMapping("/login")
    public AdminPrincipal login(@Valid @RequestBody Login login,HttpServletRequest request,HttpServletResponse response) {
        var principal=users.login(login.email(),login.password(),request.getRemoteAddr());
        request.getSession(true);request.changeSessionId();
        var context=SecurityContextHolder.createEmptyContext();
        context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(principal,null,List.of(new SimpleGrantedAuthority("ROLE_"+principal.role().name()))));
        SecurityContextHolder.setContext(context);repository.saveContext(context,request,response);
        // O frontend renova o CSRF por GET /me depois do login.
        new org.springframework.security.web.csrf.CookieCsrfTokenRepository().saveToken(null,request,response);
        return principal;
    }
    @PostMapping("/logout")
    public void logout(HttpServletRequest request,HttpServletResponse response) {
        var principal=principal();audit.record(principal.id(),"LOGOUT","USER",principal.id(),java.util.Map.of(),request.getRemoteAddr());
        if(request.getSession(false)!=null) request.getSession(false).invalidate();SecurityContextHolder.clearContext();
        var cookie=new Cookie("JSESSIONID","");cookie.setPath("/api/admin");cookie.setMaxAge(0);cookie.setHttpOnly(true);cookie.setSecure(request.isSecure());response.addCookie(cookie);
        new org.springframework.security.web.csrf.CookieCsrfTokenRepository().saveToken(null,request,response);
    }
    private AdminPrincipal principal() {
        var auth=SecurityContextHolder.getContext().getAuthentication();
        if(auth==null||!(auth.getPrincipal() instanceof AdminPrincipal)) throw new AdminAuthenticationException(401,"Authentication required");
        return (AdminPrincipal)auth.getPrincipal();
    }
}
