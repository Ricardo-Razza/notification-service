package dev.ricardo.notification.api.admin;

import dev.ricardo.notification.application.AdminUserService;
import dev.ricardo.notification.domain.AdminRole;
import dev.ricardo.notification.security.AdminPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController @RequestMapping("/api/admin/users") @RequiredArgsConstructor
public class AdminUsersController {
    private final AdminUserService users;
    @io.swagger.v3.oas.annotations.media.Schema(name="CreateAdminUser")
    public record Create(@NotBlank @Email @Size(max=320) String email,@NotBlank @Size(min=12,max=72) String password,@NotNull AdminRole role) {}
    @io.swagger.v3.oas.annotations.media.Schema(name="UpdateAdminUser")
    public record Update(AdminRole role,Boolean active,@Size(min=12,max=72) String password) {}
    @GetMapping public List<AdminUserResponse> list() {return users.list();}
    @PostMapping public AdminUserResponse create(@Valid @RequestBody Create request,@AuthenticationPrincipal AdminPrincipal actor,HttpServletRequest http) {
        return users.create(actor.id(),request.email(),request.password(),request.role(),http.getRemoteAddr());
    }
    @PatchMapping("/{id}") public AdminUserResponse update(@PathVariable("id") UUID id,@Valid @RequestBody Update request,@AuthenticationPrincipal AdminPrincipal actor,HttpServletRequest http) {
        return users.update(actor.id(),id,request.role(),request.active(),request.password(),http.getRemoteAddr());
    }
}
