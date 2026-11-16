package com.researchhub.document;

import com.researchhub.common.ApiException;
import com.researchhub.common.Values;
import com.researchhub.jobs.JobStore;
import com.researchhub.workspace.AccessService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Service
public class DocumentService {
    public enum Visibility { PRIVATE, GROUP }
    public record Document(String id, String title, String ownerId, String visibility, String filename, String status,
                           int pageCount, String error, LocalDateTime createdAt) { }
    public record ImportResult(Document document, JobStore.View job, boolean duplicate) { }
    public record Chunk(int pageNumber, int chunkIndex, String content) { }

    private final JdbcTemplate db;
    private final AccessService access;
    private final JobStore jobs;

    public DocumentService(JdbcTemplate db, AccessService access, JobStore jobs) {
        this.db = db;
        this.access = access;
        this.jobs = jobs;
    }

    @Transactional
    public ImportResult upload(String workspaceId, String userId, String title, String filename,
                               String mediaType, byte[] bytes, Visibility visibility) {
        access.requireMember(workspaceId, userId);
        if (title == null || title.isBlank() || title.length() > 240) throw ApiException.badRequest("标题长度应为 1 到 240 字符");
        if (bytes.length == 0 || bytes.length > 8 * 1024 * 1024) throw ApiException.badRequest("文件应为 1 字节到 8MB");
        Visibility scope = visibility == null ? Visibility.PRIVATE : visibility;
        String hash = Values.hash(bytes);
        db.queryForObject("SELECT id FROM workspaces WHERE id=? FOR UPDATE", String.class, workspaceId);
        var existing = db.query("""
                SELECT id FROM documents WHERE workspace_id=? AND owner_id=? AND visibility=? AND content_hash=?
                """, (rs, n) -> rs.getString(1), workspaceId, userId, scope.name(), hash);
        if (!existing.isEmpty()) {
            String id = existing.get(0);
            String jobId = db.queryForObject("SELECT id FROM jobs WHERE kind='IMPORT' AND resource_id=?", String.class, id);
            return new ImportResult(get(workspaceId, id, userId), jobs.view(jobs.get(jobId)), true);
        }
        String id = Values.id();
        db.update("""
                INSERT INTO documents(id,workspace_id,owner_id,visibility,title,filename,media_type,content_hash,
                source_bytes,status,created_at) VALUES (?,?,?,?,?,?,?,?,?,'QUEUED',?)
                """, id, workspaceId, userId, scope.name(), title.trim(), filename, mediaType, hash, bytes, Values.now());
        JobStore.Job job = jobs.enqueue(workspaceId, userId, "IMPORT", id, id, Map.of("documentId", id));
        return new ImportResult(get(workspaceId, id, userId), jobs.view(job), false);
    }

    public List<Document> list(String workspaceId, String userId) {
        access.requireMember(workspaceId, userId);
        return db.query("""
                SELECT * FROM documents WHERE workspace_id=? AND (visibility='GROUP' OR owner_id=?)
                ORDER BY created_at DESC LIMIT 100
                """, DocumentService::map, workspaceId, userId);
    }

    public Document get(String workspaceId, String id, String userId) {
        access.requireDocument(workspaceId, id, userId);
        return db.query("SELECT * FROM documents WHERE id=?", DocumentService::map, id).get(0);
    }

    public List<Chunk> chunks(String workspaceId, String id, String userId) {
        access.requireDocument(workspaceId, id, userId);
        return db.query("SELECT page_number,chunk_index,content FROM document_chunks WHERE document_id=? ORDER BY chunk_index",
                (rs, n) -> new Chunk(rs.getInt(1), rs.getInt(2), rs.getString(3)), id);
    }

    private static Document map(java.sql.ResultSet rs, int row) throws java.sql.SQLException {
        return new Document(rs.getString("id"), rs.getString("title"), rs.getString("owner_id"),
                rs.getString("visibility"), rs.getString("filename"), rs.getString("status"), rs.getInt("page_count"),
                rs.getString("error_message"), rs.getTimestamp("created_at").toLocalDateTime());
    }
}
