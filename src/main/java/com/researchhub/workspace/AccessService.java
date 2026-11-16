package com.researchhub.workspace;

import com.researchhub.common.ApiException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class AccessService {
    private final JdbcTemplate db;

    public AccessService(JdbcTemplate db) { this.db = db; }

    public String requireMember(String workspaceId, String userId) {
        return db.query("SELECT role FROM workspace_members WHERE workspace_id=? AND user_id=?",
                (rs, n) -> rs.getString(1), workspaceId, userId).stream().findFirst().orElseThrow(ApiException::notFound);
    }

    public void requireOwner(String workspaceId, String userId) {
        if (!"OWNER".equals(requireMember(workspaceId, userId))) throw ApiException.notFound();
    }

    public void requireDocument(String workspaceId, String documentId, String userId) {
        requireMember(workspaceId, userId);
        Integer count = db.queryForObject("""
                SELECT COUNT(*) FROM documents WHERE id=? AND workspace_id=? AND (visibility='GROUP' OR owner_id=?)
                """, Integer.class, documentId, workspaceId, userId);
        if (count == null || count == 0) throw ApiException.notFound();
    }

    public void requireMeeting(String workspaceId, String meetingId, String userId) {
        requireMember(workspaceId, userId);
        Integer count = db.queryForObject("SELECT COUNT(*) FROM meetings WHERE id=? AND workspace_id=?",
                Integer.class, meetingId, workspaceId);
        if (count == null || count == 0) throw ApiException.notFound();
    }
}
