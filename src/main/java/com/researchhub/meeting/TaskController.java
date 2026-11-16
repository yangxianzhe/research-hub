package com.researchhub.meeting;

import com.fasterxml.jackson.databind.JsonNode;
import com.researchhub.auth.AuthInterceptor;
import com.researchhub.common.ApiException;
import com.researchhub.common.Values;
import com.researchhub.workspace.AccessService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

@RestController
@RequestMapping("/api/workspaces/{workspaceId}")
public class TaskController {
    public enum Status { TODO, IN_PROGRESS, DONE, CANCELLED }
    public record Selected(@NotNull @Min(0) Integer index, @NotBlank @Size(max = 300) String title,
                           @Size(max = 36) String assigneeId, LocalDate dueDate) { }
    public record Confirm(@NotNull @Min(1) Integer proposalVersion,
                          @NotEmpty @Size(max = 30) List<@NotNull @Valid Selected> items) { }
    public record Transition(@NotNull Status status, @NotNull @Min(1) Integer version) { }
    public record Task(String id, String meetingId, String title, String assigneeId, String assigneeName,
                       LocalDate dueDate, String status, int version, LocalDateTime createdAt) { }

    private final JdbcTemplate db;
    private final AccessService access;
    private final MeetingService meetings;

    public TaskController(JdbcTemplate db, AccessService access, MeetingService meetings) {
        this.db = db;
        this.access = access;
        this.meetings = meetings;
    }

    @PostMapping("/meetings/{meetingId}/confirm-actions")
    @Transactional
    public List<Task> confirm(@PathVariable String workspaceId, @PathVariable String meetingId,
                              @Valid @RequestBody Confirm body, HttpServletRequest request) {
        String userId = AuthInterceptor.userId(request);
        meetings.requireEditor(workspaceId, meetingId, userId);
        db.queryForObject("SELECT id FROM meetings WHERE id=? FOR UPDATE", String.class, meetingId);
        var meeting = meetings.get(workspaceId, meetingId, userId);
        if (meeting.proposals() == null || meeting.proposalsVersion() != body.proposalVersion()
                || meeting.minutesVersion() != body.proposalVersion()) throw ApiException.conflict("待办提议已过期，请刷新后重新提取");
        JsonNode proposals = meeting.proposals().path("items");
        HashSet<Integer> seen = new HashSet<>();
        List<String> ids = new ArrayList<>();
        for (Selected selected : body.items()) {
            if (!seen.add(selected.index()) || selected.index() >= proposals.size()) throw ApiException.badRequest("待办序号重复或不存在");
            if (selected.assigneeId() != null) access.requireMember(workspaceId, selected.assigneeId());
            String hash = Values.hash(Values.json(selected));
            var old = db.query("SELECT id,confirmation_hash FROM action_tasks WHERE meeting_id=? AND proposal_version=? AND proposal_index=?",
                    (rs, n) -> List.of(rs.getString(1), rs.getString(2)), meetingId, body.proposalVersion(), selected.index());
            if (!old.isEmpty()) {
                if (!hash.equals(old.get(0).get(1))) throw ApiException.conflict("该提议已按另一份内容确认，请使用已创建的待办");
                ids.add(old.get(0).get(0));
                continue;
            }
            String id = Values.id();
            db.update("""
                    INSERT INTO action_tasks(id,workspace_id,meeting_id,created_by,assignee_id,title,due_date,status,
                    proposal_version,proposal_index,confirmation_hash,created_at) VALUES (?,?,?,?,?,?,?,'TODO',?,?,?,?)
                    """, id, workspaceId, meetingId, userId, selected.assigneeId(), selected.title().trim(), selected.dueDate(),
                    body.proposalVersion(), selected.index(), hash, Values.now());
            ids.add(id);
        }
        return ids.stream().map(this::get).toList();
    }

    @GetMapping("/tasks")
    public List<Task> list(@PathVariable String workspaceId, @RequestParam(required = false) Status status,
                           HttpServletRequest request) {
        access.requireMember(workspaceId, AuthInterceptor.userId(request));
        String sql = "SELECT t.*,u.display_name FROM action_tasks t LEFT JOIN app_users u ON u.id=t.assignee_id WHERE t.workspace_id=?";
        if (status != null) return db.query(sql + " AND t.status=? ORDER BY t.created_at DESC LIMIT 200", this::map, workspaceId, status.name());
        return db.query(sql + " ORDER BY t.created_at DESC LIMIT 200", this::map, workspaceId);
    }

    @PatchMapping("/tasks/{id}")
    @Transactional
    public Task transition(@PathVariable String workspaceId, @PathVariable String id, @Valid @RequestBody Transition body,
                            HttpServletRequest request) {
        String userId = AuthInterceptor.userId(request);
        access.requireMember(workspaceId, userId);
        var rows = db.query("SELECT id FROM action_tasks WHERE id=? AND workspace_id=? FOR UPDATE", (rs, n) -> rs.getString(1), id, workspaceId);
        if (rows.isEmpty()) throw ApiException.notFound();
        Task task = get(id);
        String creator = db.queryForObject("SELECT created_by FROM action_tasks WHERE id=?", String.class, id);
        if (!userId.equals(task.assigneeId()) && !userId.equals(creator) && !"OWNER".equals(access.requireMember(workspaceId, userId)))
            throw ApiException.notFound();
        if (task.version() != body.version()) throw ApiException.conflict("待办已被更新，请重新读取版本");
        Status current = Status.valueOf(task.status());
        if (current == body.status()) return task;
        boolean allowed = switch (current) {
            case TODO -> body.status() == Status.IN_PROGRESS || body.status() == Status.DONE || body.status() == Status.CANCELLED;
            case IN_PROGRESS -> body.status() == Status.TODO || body.status() == Status.DONE || body.status() == Status.CANCELLED;
            case DONE, CANCELLED -> body.status() == Status.TODO;
        };
        if (!allowed) throw ApiException.conflict("不允许此状态流转，可先重新打开为 TODO");
        db.update("UPDATE action_tasks SET status=?,version=version+1 WHERE id=? AND version=?", body.status().name(), id, body.version());
        return get(id);
    }

    private Task get(String id) {
        return db.query("SELECT t.*,u.display_name FROM action_tasks t LEFT JOIN app_users u ON u.id=t.assignee_id WHERE t.id=?", this::map, id)
                .stream().findFirst().orElseThrow(ApiException::notFound);
    }

    private Task map(java.sql.ResultSet rs, int row) throws java.sql.SQLException {
        var date = rs.getDate("due_date");
        return new Task(rs.getString("id"), rs.getString("meeting_id"), rs.getString("title"), rs.getString("assignee_id"),
                rs.getString("display_name"), date == null ? null : date.toLocalDate(), rs.getString("status"), rs.getInt("version"),
                rs.getTimestamp("created_at").toLocalDateTime());
    }
}
