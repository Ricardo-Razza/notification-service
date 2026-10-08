package dev.ricardo.notification.security;

import dev.ricardo.notification.infrastructure.persistence.AdminUserRepository;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;
import java.util.List;

public class AdminSessionValidationFilter extends OncePerRequestFilter {
    private final AdminUserRepository users;
    public AdminSessionValidationFilter(AdminUserRepository users) {this.users=users;}
    @Override protected void doFilterInternal(HttpServletRequest req,HttpServletResponse res,FilterChain chain) throws ServletException,IOException {
        var context=SecurityContextHolder.getContext();var auth=context.getAuthentication();
        if(auth!=null&&auth.getPrincipal() instanceof AdminPrincipal principal) {
            var user=users.findById(principal.id()).orElse(null);
            if(user==null||!user.isActive()) {if(req.getSession(false)!=null) req.getSession(false).invalidate();SecurityContextHolder.clearContext();}
            else context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(new AdminPrincipal(user.getId(),user.getEmail(),user.getRole()),null,
                    List.of(new SimpleGrantedAuthority("ROLE_"+user.getRole().name()))));
        }
        res.setHeader("Cache-Control","no-store");chain.doFilter(req,res);
    }
}
