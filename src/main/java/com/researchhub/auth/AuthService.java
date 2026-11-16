package com.researchhub.auth;

import com.researchhub.common.ApiException;
import com.researchhub.common.Values;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.Locale;

@Service
public class AuthService {
    public record User(String id, String email, String displayName) { }
    public record Login(User user, String token, Instant expiresAt) { }

    private final JdbcTemplate db;
    private final BCryptPasswordEncoder passwords = new BCryptPasswordEncoder(10);
    private final SecureRandom random = new SecureRandom();

    public AuthService(JdbcTemplate db) { this.db = db; }

    @Transactional
    public Login register(String email, String displayName, String password) {
        checkPasswordLength(password);
        String id = Values.id();
        String normalizedEmail = email.trim().toLowerCase(Locale.ROOT);
        db.update("INSERT INTO app_users(id,email,display_name,password_hash,created_at) VALUES (?,?,?,?,?)",
                id, normalizedEmail, displayName.trim(), passwords.encode(password), Values.now());
        return issue(new User(id, normalizedEmail, displayName.trim()));
    }

    @Transactional
    public Login login(String email, String password) {
        checkPasswordLength(password);
        var rows = db.query("SELECT * FROM app_users WHERE email=?", (rs, n) ->
                new Credential(new User(rs.getString("id"), rs.getString("email"), rs.getString("display_name")),
                        rs.getString("password_hash")), email.trim().toLowerCase(Locale.ROOT));
        if (rows.isEmpty() || !passwords.matches(password, rows.get(0).hash())) {
            throw ApiException.unauthorized();
        }
        return issue(rows.get(0).user());
    }

    public User authenticate(String token) {
        if (token == null || token.length() > 200 || token.isBlank()) throw ApiException.unauthorized();
        return db.query("""
                SELECT u.id,u.email,u.display_name FROM app_users u JOIN auth_sessions s ON s.user_id=u.id
                WHERE s.token_hash=? AND s.expires_at>?
                """, (rs, n) -> new User(rs.getString("id"), rs.getString("email"), rs.getString("display_name")),
                Values.hash(token), Values.now()).stream().findFirst().orElseThrow(ApiException::unauthorized);
    }

    public void logout(String token) {
        db.update("DELETE FROM auth_sessions WHERE token_hash=?", Values.hash(token));
    }

    private Login issue(User user) {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        Instant expiry = Instant.now().plus(7, ChronoUnit.DAYS);
        db.update("DELETE FROM auth_sessions WHERE expires_at<?", Values.now());
        db.update("INSERT INTO auth_sessions(token_hash,user_id,expires_at) VALUES (?,?,?)",
                Values.hash(token), user.id(), Values.timestamp(expiry));
        return new Login(user, token, expiry);
    }

    private static void checkPasswordLength(String password) {
        if (password.getBytes(StandardCharsets.UTF_8).length > 72) {
            throw ApiException.badRequest("密码的 UTF-8 编码长度最多为 72 字节");
        }
    }

    private record Credential(User user, String hash) { }
}
