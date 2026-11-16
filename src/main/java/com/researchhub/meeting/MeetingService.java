package com.researchhub.meeting;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.researchhub.common.ApiException;
import com.researchhub.common.Values;
import com.researchhub.jobs.JobStore;
import com.researchhub.workspace.AccessService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Service
public class MeetingService {
    public record Meeting(String id, String title, LocalDate meetingDate, LocalDate periodStart, LocalDate periodEnd,
                          JsonNode draft, String minutes, int minutesVersion, JsonNode proposals, int proposalsVersion,
                          LocalDateTime createdAt, String createdBy) { }
    private final JdbcTemplate db;
    private final AccessService access;
    private final JobStore jobs;
    private final ObjectMapper mapper;

    public MeetingService(JdbcTemplate db, AccessService access, JobStore jobs, ObjectMapper mapper) {
        this.db = db;
        this.access = access;
        this.jobs = jobs;
        this.mapper = mapper;
    }

    @Transactional
    public Meeting create(String workspaceId, String userId, String title, LocalDate meetingDate, LocalDate start, LocalDate end) {
        access.requireMember(workspaceId, userId);
        if (start.isAfter(end)) throw ApiException.badRequest("periodStart 应早于或等于 periodEnd");
        if (java.time.temporal.ChronoUnit.DAYS.between(start, end) > 93) throw ApiException.badRequest("第一版一次最多整理 94 天资料");
        String id = Values.id();
        db.update("""
                INSERT INTO meetings(id,workspace_id,created_by,title,meeting_date,period_start,period_end,created_at)
                VALUES (?,?,?,?,?,?,?,?)
                """, id, workspaceId, userId, title.trim(), meetingDate, start, end, Values.now());
        return get(workspaceId, id, userId);
    }

    public Meeting get(String workspaceId, String id, String userId) {
        access.requireMeeting(workspaceId, id, userId);
        return db.query("SELECT * FROM meetings WHERE id=?", this::map, id).get(0);
    }

    public List<Meeting> list(String workspaceId, String userId) {
        access.requireMember(workspaceId, userId);
        return db.query("SELECT * FROM meetings WHERE workspace_id=? ORDER BY meeting_date DESC,created_at DESC LIMIT 100", this::map, workspaceId);
    }

    @Transactional
    public Meeting saveMinutes(String workspaceId, String id, String userId, String minutes, int version) {
        requireEditor(workspaceId, id, userId);
        int changed = db.update("""
                UPDATE meetings SET minutes=?,minutes_version=minutes_version+1,proposals_json=NULL,proposals_version=0
                WHERE id=? AND minutes_version=?
                """, minutes, id, version);
        if (changed == 0) throw ApiException.conflict("纪要已更新，请重新读取版本后保存");
        return get(workspaceId, id, userId);
    }

    @Transactional
    public JobStore.View enqueue(String workspaceId, String id, String userId, String kind, String requestKey) {
        requireEditor(workspaceId, id, userId);
        // 工作区锁统一排队请求的锁顺序，避免两个成员同时创建重复的资源任务。
        db.queryForObject("SELECT id FROM workspaces WHERE id=? FOR UPDATE", String.class, workspaceId);
        Meeting meeting = get(workspaceId, id, userId);
        Map<String, Object> payload = "MINUTE_ACTIONS".equals(kind)
                ? Map.of("minutesVersion", meeting.minutesVersion()) : Map.of("meetingId", id);
        var prior = db.query("SELECT id FROM jobs WHERE workspace_id=? AND requested_by=? AND kind=? AND request_key=?",
                (rs, n) -> rs.getString(1), workspaceId, userId, kind, requestKey);
        if (!prior.isEmpty()) return jobs.view(jobs.enqueue(workspaceId, userId, kind, id, requestKey, payload));
        Integer active = db.queryForObject("SELECT COUNT(*) FROM jobs WHERE resource_id=? AND kind=? AND status IN ('QUEUED','RUNNING')",
                Integer.class, id, kind);
        if (active != null && active > 0) throw ApiException.conflict("该组会已有同类型任务执行中，请先等待或取消原任务");
        if ("MINUTE_ACTIONS".equals(kind)) {
            if (meeting.minutes().isBlank()) throw ApiException.badRequest("请先保存文字纪要");
            if (meeting.proposals() != null) throw ApiException.conflict("当前纪要已有待办提议，可确认提议或编辑纪要后重新提取");
        }
        return jobs.view(jobs.enqueue(workspaceId, userId, kind, id, requestKey, payload));
    }

    public void requireEditor(String workspaceId, String id, String userId) {
        String role = access.requireMember(workspaceId, userId);
        String creator = db.query("SELECT created_by FROM meetings WHERE id=? AND workspace_id=?",
                (rs, n) -> rs.getString(1), id, workspaceId).stream().findFirst().orElseThrow(ApiException::notFound);
        if (!creator.equals(userId) && !"OWNER".equals(role)) throw ApiException.notFound();
    }

    private Meeting map(java.sql.ResultSet rs, int row) throws java.sql.SQLException {
        return new Meeting(rs.getString("id"), rs.getString("title"), rs.getDate("meeting_date").toLocalDate(),
                rs.getDate("period_start").toLocalDate(), rs.getDate("period_end").toLocalDate(), json(rs.getString("draft_json")),
                rs.getString("minutes"), rs.getInt("minutes_version"), json(rs.getString("proposals_json")),
                rs.getInt("proposals_version"), rs.getTimestamp("created_at").toLocalDateTime(), rs.getString("created_by"));
    }

    private JsonNode json(String value) {
        if (value == null) return null;
        try { return mapper.readTree(value); }
        catch (Exception e) { throw new IllegalStateException("Invalid stored meeting", e); }
    }
}
