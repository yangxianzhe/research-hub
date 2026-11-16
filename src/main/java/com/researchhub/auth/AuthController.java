package com.researchhub.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
    private final AuthService auth;

    public AuthController(AuthService auth) { this.auth = auth; }

    public record Register(@NotBlank @Email @Size(max = 254) String email,
                           @NotBlank @Size(max = 80) String displayName,
                           @NotBlank @Size(min = 8, max = 72) String password) { }
    public record Credentials(@NotBlank @Email @Size(max = 254) String email,
                              @NotBlank @Size(max = 72) String password) { }

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    public AuthService.Login register(@Valid @RequestBody Register request) {
        return auth.register(request.email(), request.displayName(), request.password());
    }

    @PostMapping("/login")
    public AuthService.Login login(@Valid @RequestBody Credentials request) {
        return auth.login(request.email(), request.password());
    }

    @GetMapping("/me")
    public AuthService.User me(HttpServletRequest request) {
        return (AuthService.User) request.getAttribute(AuthInterceptor.USER);
    }

    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout(HttpServletRequest request) {
        auth.logout(request.getHeader("Authorization").substring(7).trim());
    }
}
