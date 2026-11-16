package com.researchhub.jobs;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.researchhub.HubProperties;
import com.researchhub.common.ApiException;
import com.researchhub.common.Values;
import com.researchhub.workspace.AccessService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

@Service
public class JobStore {
    public record Job(String id, String workspaceId, String requestedBy, String kind, String resourceId,
                      String status, int attempts, String leaseToken, String payload, String result, String error,
                      LocalDateTime createdAt, LocalDateTime updatedAt) { }
    public record View(String id, String workspaceId, String kind, String resourceId, String status, int attempts,
                       JsonNode result, String error, LocalDateTime createdAt, LocalDateTime updatedAt) { }
    public record Event(String phase, String message, LocalDateTime createdAt) { }

    private static final RowMapper<Job> MAPPER = (rs, n) -> new Job(rs.getString("id"), rs.getString("workspace_id"),
            rs.getString("requested_by"), rs.getString("kind"), rs.getString("resource_id"), rs.getString("status"),
            rs.getInt("attempts"), rs.getString("lease_token"), rs.getString("payload_json"), rs.getString("result_json"),
            rs.getString("error_message"), rs.getTimestamp("created_at").toLocalDateTime(),
            rs.getTimestamp("updated_at").toLocalDateTime());

    private final JdbcTemplate db;
    private final AccessService access;
    private final HubProperties properties;
    private final ObjectMapper mapper;

    public JobStore(JdbcTemplate db, AccessService access, HubProperties properties, ObjectMapper mapper) {
        this.db = db;
        this.access = access;
        this.properties = properties;
        this.mapper = mapper;
    }

    @Transactional
    public Job enqueue(String workspaceId, String userId, String kind, String resourceId, String key, Object payload) {
        access.requireMember(workspaceId, userId);
        if (key == null || !key.matches("[A-Za-z0-9._-]{1,100}")) {
            throw ApiException.badRequest("请提供 1 到 100 位字母、数字或 ._- 组成的 Idempotency-Key");
        }
        // 同一工作区内先串行完成去重和入队，业务结果由独立工作线程处理。
        db.queryForObject("SELECT id FROM workspaces WHERE id=? FOR UPDATE", String.class, workspaceId);
        String json = Values.json(payload);
        String hash = Values.hash(resourceId + "\n" + json);
        var existing = db.query("SELECT * FROM jobs WHERE workspace_id=? AND requested_by=? AND kind=? AND request_key=?",
                MAPPER, workspaceId, userId, kind, key);
        if (!existing.isEmpty()) {
            String oldHash = db.queryForObject("SELECT request_hash FROM jobs WHERE id=?", String.class, existing.get(0).id());
            if (!hash.equals(oldHash)) throw ApiException.conflict("相同 Idempotency-Key 已用于另一份请求，请更换该值");
            return existing.get(0);
        }
        String id = Values.id();
        db.update("""
                INSERT INTO jobs(id,workspace_id,requested_by,kind,resource_id,request_key,request_hash,payload_json,
                status,available_at,created_at,updated_at) VALUES (?,?,?,?,?,?,?,?,'QUEUED',?,?,?)
                """, id, workspaceId, userId, kind, resourceId, key, hash, json, Values.now(), Values.now(), Values.now());
        event(id, "QUEUED", "任务已保存，等待处理");
        return get(id);
    }

    public Job get(String id) {
        return db.query("SELECT * FROM jobs WHERE id=?", MAPPER, id).stream().findFirst().orElseThrow(ApiException::notFound);
    }

    public Job requireOwned(String id, String userId) {
        Job job = get(id);
        access.requireMember(job.workspaceId(), userId);
        if (!job.requestedBy().equals(userId)) throw ApiException.notFound();
        return job;
    }

    public View view(Job job) {
        try {
            return new View(job.id(), job.workspaceId(), job.kind(), job.resourceId(), job.status(), job.attempts(),
                    job.result() == null ? null : mapper.readTree(job.result()), job.error(), job.createdAt(), job.updatedAt());
        } catch (Exception e) {
            throw new IllegalStateException("Invalid stored job result", e);
        }
    }

    public List<View> list(String workspaceId, String userId) {
        access.requireMember(workspaceId, userId);
        return db.query("SELECT * FROM jobs WHERE workspace_id=? AND requested_by=? ORDER BY created_at DESC LIMIT 100",
                MAPPER, workspaceId, userId).stream().map(this::view).toList();
    }

    public List<Event> events(String id, String userId) {
        requireOwned(id, userId);
        return db.query("SELECT * FROM job_events WHERE job_id=? ORDER BY id", (rs, n) ->
                new Event(rs.getString("phase"), rs.getString("message"), rs.getTimestamp("created_at").toLocalDateTime()), id);
    }

    @Transactional
    public Optional<Job> claim() {
        // 最后一次执行若进程崩溃，也需要从 RUNNING 收敛到终态。
        var exhausted = db.query("SELECT * FROM jobs WHERE status='RUNNING' AND lease_until<? AND attempts>=?",
                MAPPER, Values.now(), properties.jobs().maxAttempts());
        for (Job job : exhausted) {
            int changed = db.update("""
                    UPDATE jobs SET status='FAILED',error_message='执行进程中断且已达到重试上限',updated_at=?
                    WHERE id=? AND status='RUNNING' AND lease_until<? AND attempts>=?
                    """, Values.now(), job.id(), Values.now(), properties.jobs().maxAttempts());
            if (changed > 0) {
                documentState(job, "FAILED", "执行进程中断且已达到重试上限");
                event(job.id(), "FAILED", "执行进程中断且已达到重试上限");
            }
        }
        var candidates = db.query("""
                SELECT * FROM jobs WHERE attempts<? AND
                ((status='QUEUED' AND available_at<=?) OR (status='RUNNING' AND lease_until<?))
                ORDER BY created_at LIMIT 10
                """, MAPPER, properties.jobs().maxAttempts(), Values.now(), Values.now());
        for (Job candidate : candidates) {
            String token = Values.id();
            int changed = db.update("""
                    UPDATE jobs SET status='RUNNING',attempts=attempts+1,lease_token=?,lease_until=?,updated_at=?
                    WHERE id=? AND attempts<? AND
                    ((status='QUEUED' AND available_at<=?) OR (status='RUNNING' AND lease_until<?))
                    """, token, Values.timestamp(Instant.now().plusSeconds(properties.jobs().leaseSeconds())), Values.now(),
                    candidate.id(), properties.jobs().maxAttempts(), Values.now(), Values.now());
            if (changed == 1) {
                Job job = get(candidate.id());
                documentState(job, "PROCESSING", null);
                event(job.id(), "RUNNING", "开始第 " + job.attempts() + " 次执行");
                return Optional.of(job);
            }
        }
        return Optional.empty();
    }

    public boolean renew(Job job) {
        return db.update("""
                UPDATE jobs SET lease_until=? WHERE id=? AND status='RUNNING' AND lease_token=? AND lease_until>=?
                """, Values.timestamp(Instant.now().plusSeconds(properties.jobs().leaseSeconds())), job.id(), job.leaseToken(),
                Values.now()) == 1;
    }

    @Transactional
    public boolean succeed(Job execution, Object result, Runnable persistResult) {
        Job current = locked(execution.id());
        if (!ownsLease(current, execution)) return false;
        // 锁定任务后在同一事务中发布业务结果，旧工作线程无法覆盖新租约的结果。
        persistResult.run();
        db.update("UPDATE jobs SET status='SUCCEEDED',result_json=?,error_message=NULL,lease_token=NULL,lease_until=NULL,updated_at=? WHERE id=?",
                Values.json(result), Values.now(), execution.id());
        event(execution.id(), "SUCCEEDED", "处理完成");
        return true;
    }

    @Transactional
    public void fail(Job execution, String message, boolean retryable) {
        Job current = locked(execution.id());
        if (!ownsLease(current, execution)) return;
        boolean retry = retryable && current.attempts() < properties.jobs().maxAttempts();
        String status = retry ? "QUEUED" : "FAILED";
        String safeMessage = message == null ? "处理失败" : message.substring(0, Math.min(500, message.length()));
        db.update("""
                UPDATE jobs SET status=?,error_message=?,lease_token=NULL,lease_until=NULL,available_at=?,updated_at=? WHERE id=?
                """, status, safeMessage, Values.timestamp(Instant.now().plusSeconds(2L * current.attempts())), Values.now(), current.id());
        documentState(current, retry ? "QUEUED" : "FAILED", safeMessage);
        event(current.id(), status, safeMessage);
    }

    @Transactional
    public View cancel(String id, String userId) {
        requireOwned(id, userId);
        Job job = locked(id);
        if (!List.of("QUEUED", "RUNNING").contains(job.status())) throw ApiException.conflict("只有等待或执行中的任务可以取消");
        db.update("UPDATE jobs SET status='CANCELLED',lease_token=NULL,lease_until=NULL,updated_at=? WHERE id=?", Values.now(), id);
        documentState(job, "FAILED", "任务已取消，可手动重试");
        event(id, "CANCELLED", "用户取消任务，后续结果将被丢弃");
        return view(get(id));
    }

    @Transactional
    public View retry(String id, String userId) {
        requireOwned(id, userId);
        Job job = locked(id);
        if (!List.of("FAILED", "CANCELLED").contains(job.status())) throw ApiException.conflict("只有失败或取消的任务可以重试");
        db.update("""
                UPDATE jobs SET status='QUEUED',attempts=0,error_message=NULL,result_json=NULL,
                lease_token=NULL,lease_until=NULL,available_at=?,updated_at=? WHERE id=?
                """, Values.now(), Values.now(), id);
        documentState(job, "QUEUED", null);
        event(id, "QUEUED", "用户请求重新执行");
        return view(get(id));
    }

    private Job locked(String id) {
        return db.query("SELECT * FROM jobs WHERE id=? FOR UPDATE", MAPPER, id).stream().findFirst().orElseThrow(ApiException::notFound);
    }

    private boolean ownsLease(Job current, Job execution) {
        if (!"RUNNING".equals(current.status()) || !execution.leaseToken().equals(current.leaseToken())) return false;
        var until = db.queryForObject("SELECT lease_until FROM jobs WHERE id=?", java.sql.Timestamp.class, current.id());
        return until != null && until.toLocalDateTime().toInstant(ZoneOffset.UTC).isAfter(Instant.now());
    }

    private void documentState(Job job, String status, String error) {
        if ("IMPORT".equals(job.kind())) {
            db.update("UPDATE documents SET status=?,error_message=? WHERE id=?", status, error, job.resourceId());
        }
    }

    private void event(String id, String phase, String message) {
        db.update("INSERT INTO job_events(job_id,phase,message,created_at) VALUES (?,?,?,?)", id, phase, message, Values.now());
    }

    public void progress(Job execution, String phase, String message) {
        db.update("""
                INSERT INTO job_events(job_id,phase,message,created_at)
                SELECT id,?,?,? FROM jobs WHERE id=? AND status='RUNNING' AND lease_token=? AND lease_until>?
                """, phase, message, Values.now(), execution.id(), execution.leaseToken(), Values.now());
    }
}
