package com.researchhub.document;

import com.researchhub.auth.AuthInterceptor;
import com.researchhub.common.ApiException;
import com.researchhub.common.Values;
import com.researchhub.workspace.AccessService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;

@RestController
@RequestMapping("/api/workspaces/{workspaceId}/documents/{documentId}/notes")
public class ReadingController {
    public enum ReadingStatus { READING, DONE, BLOCKED }
    public record Save(@NotNull ReadingStatus readingStatus, @NotNull DocumentService.Visibility visibility,
                       @NotNull @Size(max = 20000) String content, @NotNull @Min(0) Integer version) { }
    public record Note(String id, String userId, String displayName, String visibility, String readingStatus,
                       String content, int version, LocalDateTime updatedAt) { }

    private final JdbcTemplate db;
    private final AccessService access;

    public ReadingController(JdbcTemplate db, AccessService access) { this.db = db; this.access = access; }

    @GetMapping
    public List<Note> list(@PathVariable String workspaceId, @PathVariable String documentId, HttpServletRequest request) {
        String userId = AuthInterceptor.userId(request);
        access.requireDocument(workspaceId, documentId, userId);
        return db.query("""
                SELECT n.*,u.display_name FROM reading_notes n JOIN app_users u ON u.id=n.user_id
                WHERE n.document_id=? AND (n.visibility='GROUP' OR n.user_id=?) ORDER BY n.updated_at DESC
                """, (rs, n) -> new Note(rs.getString("id"), rs.getString("user_id"), rs.getString("display_name"),
                rs.getString("visibility"), rs.getString("reading_status"), rs.getString("content"), rs.getInt("version"),
                rs.getTimestamp("updated_at").toLocalDateTime()), documentId, userId);
    }

    @PutMapping("/mine")
    @Transactional
    public Note save(@PathVariable String workspaceId, @PathVariable String documentId, @Valid @RequestBody Save body,
                     HttpServletRequest request) {
        String userId = AuthInterceptor.userId(request);
        access.requireDocument(workspaceId, documentId, userId);
        String documentVisibility = db.queryForObject("SELECT visibility FROM documents WHERE id=?", String.class, documentId);
        if (body.visibility() == DocumentService.Visibility.GROUP && !"GROUP".equals(documentVisibility)) {
            throw ApiException.badRequest("私有资料的阅读记录应保持 PRIVATE");
        }
        if (body.version() == 0) {
            db.update("""
                    INSERT INTO reading_notes(id,document_id,user_id,visibility,reading_status,content,updated_at)
                    VALUES (?,?,?,?,?,?,?)
                    """, Values.id(), documentId, userId, body.visibility().name(), body.readingStatus().name(), body.content(), Values.now());
        } else {
            int changed = db.update("""
                    UPDATE reading_notes SET visibility=?,reading_status=?,content=?,version=version+1,updated_at=?
                    WHERE document_id=? AND user_id=? AND version=?
                    """, body.visibility().name(), body.readingStatus().name(), body.content(), Values.now(), documentId, userId, body.version());
            if (changed == 0) throw ApiException.conflict("阅读记录已更新，请重新读取版本后保存");
        }
        return list(workspaceId, documentId, request).stream().filter(note -> note.userId().equals(userId)).findFirst().orElseThrow();
    }
}
