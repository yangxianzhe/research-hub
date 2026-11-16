package com.researchhub.auth;

import com.researchhub.common.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

@Component
public class AuthInterceptor implements HandlerInterceptor {
    public static final String USER = "authenticatedUser";
    private final AuthService auth;

    public AuthInterceptor(AuthService auth) { this.auth = auth; }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String header = request.getHeader("Authorization");
        if (header == null || !header.regionMatches(true, 0, "Bearer ", 0, 7)) throw ApiException.unauthorized();
        request.setAttribute(USER, auth.authenticate(header.substring(7).trim()));
        response.setHeader("Cache-Control", "no-store");
        return true;
    }

    public static String userId(HttpServletRequest request) {
        return ((AuthService.User) request.getAttribute(USER)).id();
    }
}
