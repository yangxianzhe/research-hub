package com.researchhub.workspace;

import com.researchhub.auth.AuthInterceptor;
import com.researchhub.common.ApiException;
import com.researchhub.common.Values;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;

@RestController
@RequestMapping("/api/workspaces")
public class WorkspaceController {
    private final JdbcTemplate db;
    private final AccessService access;

    public WorkspaceController(JdbcTemplate db, AccessService access) {
        this.db = db;
        this.access = access;
    }

    public record Create(@NotBlank @Size(max = 120) String name) { }
    public record AddMember(@NotBlank @Email @Size(max = 254) String email) { }
    public record Workspace(String id, String name, String role, LocalDateTime createdAt) { }
    public record Member(String userId, String email, String displayName, String role) { }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    public Workspace create(@Valid @RequestBody Create body, HttpServletRequest request) {
        String userId = AuthInterceptor.userId(request);
        String id = Values.id();
        var now = Values.now();
        db.update("INSERT INTO workspaces(id,name,created_by,created_at) VALUES (?,?,?,?)", id, body.name().trim(), userId, now);
        db.update("INSERT INTO workspace_members(workspace_id,user_id,role) VALUES (?,?,'OWNER')", id, userId);
        return new Workspace(id, body.name().trim(), "OWNER", now.toLocalDateTime());
    }

    @GetMapping
    public List<Workspace> list(HttpServletRequest request) {
        return db.query("""
                SELECT w.*,m.role FROM workspaces w JOIN workspace_members m ON m.workspace_id=w.id
                WHERE m.user_id=? ORDER BY w.created_at DESC
                """, (rs, n) -> new Workspace(rs.getString("id"), rs.getString("name"), rs.getString("role"),
                rs.getTimestamp("created_at").toLocalDateTime()), AuthInterceptor.userId(request));
    }

    @GetMapping("/{workspaceId}/members")
    public List<Member> members(@PathVariable String workspaceId, HttpServletRequest request) {
        access.requireMember(workspaceId, AuthInterceptor.userId(request));
        return db.query("""
                SELECT u.id,u.email,u.display_name,m.role FROM app_users u
                JOIN workspace_members m ON m.user_id=u.id WHERE m.workspace_id=? ORDER BY u.created_at
                """, (rs, n) -> new Member(rs.getString("id"), rs.getString("email"), rs.getString("display_name"),
                rs.getString("role")), workspaceId);
    }

    @PostMapping("/{workspaceId}/members")
    @Transactional
    public List<Member> add(@PathVariable String workspaceId, @Valid @RequestBody AddMember body, HttpServletRequest request) {
        access.requireOwner(workspaceId, AuthInterceptor.userId(request));
        String userId = db.query("SELECT id FROM app_users WHERE email=?", (rs, n) -> rs.getString(1),
                body.email().trim().toLowerCase(Locale.ROOT)).stream().findFirst()
                .orElseThrow(() -> ApiException.badRequest("请先让该成员注册账号"));
        db.update("""
                INSERT INTO workspace_members(workspace_id,user_id,role)
                SELECT ?,?,'MEMBER' WHERE NOT EXISTS
                (SELECT 1 FROM workspace_members WHERE workspace_id=? AND user_id=?)
                """, workspaceId, userId, workspaceId, userId);
        return members(workspaceId, request);
    }
}
