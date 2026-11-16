package com.researchhub.evaluation;

import com.researchhub.auth.AuthInterceptor;
import com.researchhub.common.Values;
import com.researchhub.workspace.AccessService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;

@RestController
@RequestMapping("/api/workspaces/{workspaceId}/measurements")
public class EvaluationController {
    public enum Scenario { MEETING_PREPARATION, SOURCE_LOOKUP, ACTION_EXTRACTION }
    public record Record(@NotNull Scenario scenario, @NotNull @Min(1) @Max(604800000) Long baselineMs,
                         @NotNull @Min(0) @Max(604800000) Long assistedMs,
                         @NotNull @Min(0) @Max(604800000) Long reviewMs,
                         @NotNull Boolean qualityAccepted, @NotNull @Size(max = 1000) String notes) { }
    public record Summary(String scenario, int samples, int participants, double medianBaselineMs,
                          double medianAssistedIncludingReviewMs, double medianReductionPercent, double acceptanceRate,
                          String method) { }
    public record Measurement(String id, String userId, String scenario, long baselineMs, long assistedMs,
                              long reviewMs, boolean qualityAccepted, String notes, java.time.LocalDateTime createdAt) { }
    private final JdbcTemplate db;
    private final AccessService access;

    public EvaluationController(JdbcTemplate db, AccessService access) { this.db = db; this.access = access; }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public java.util.Map<String, String> record(@PathVariable String workspaceId, @Valid @RequestBody Record body, HttpServletRequest request) {
        String userId = AuthInterceptor.userId(request);
        access.requireMember(workspaceId, userId);
        String id = Values.id();
        db.update("""
                INSERT INTO efficiency_measurements(id,workspace_id,user_id,scenario,baseline_ms,assisted_ms,review_ms,
                quality_accepted,notes,created_at) VALUES (?,?,?,?,?,?,?,?,?,?)
                """, id, workspaceId, userId, body.scenario().name(), body.baselineMs(), body.assistedMs(), body.reviewMs(),
                body.qualityAccepted(), body.notes(), Values.now());
        return java.util.Map.of("id", id);
    }

    @GetMapping
    public List<Summary> summary(@PathVariable String workspaceId, HttpServletRequest request) {
        access.requireMember(workspaceId, AuthInterceptor.userId(request));
        List<Summary> summaries = new ArrayList<>();
        for (Scenario scenario : Scenario.values()) {
            var samples = db.query("""
                    SELECT user_id,baseline_ms,assisted_ms,review_ms,quality_accepted FROM efficiency_measurements
                    WHERE workspace_id=? AND scenario=? ORDER BY created_at
                    """, (rs, n) -> new Sample(rs.getString(1), rs.getLong(2), rs.getLong(3) + rs.getLong(4), rs.getBoolean(5)),
                    workspaceId, scenario.name());
            if (samples.isEmpty()) continue;
            summaries.add(new Summary(scenario.name(), samples.size(), (int) samples.stream().map(Sample::userId).distinct().count(),
                    median(samples.stream().map(sample -> (double) sample.baseline()).toList()),
                    median(samples.stream().map(sample -> (double) sample.assisted()).toList()),
                    median(samples.stream().map(sample -> 100.0 * (sample.baseline() - sample.assisted()) / sample.baseline()).toList()),
                    samples.stream().filter(Sample::accepted).count() / (double) samples.size(),
                    "配对人工记录，assistedMs + reviewMs 为辅助流程总耗时；包含质量未通过样本，未自动剔除失败"));
        }
        return summaries;
    }

    @GetMapping("/records")
    public List<Measurement> records(@PathVariable String workspaceId, HttpServletRequest request) {
        access.requireMember(workspaceId, AuthInterceptor.userId(request));
        return db.query("SELECT * FROM efficiency_measurements WHERE workspace_id=? ORDER BY created_at DESC LIMIT 200",
                (rs, n) -> new Measurement(rs.getString("id"), rs.getString("user_id"), rs.getString("scenario"),
                        rs.getLong("baseline_ms"), rs.getLong("assisted_ms"), rs.getLong("review_ms"),
                        rs.getBoolean("quality_accepted"), rs.getString("notes"), rs.getTimestamp("created_at").toLocalDateTime()), workspaceId);
    }

    private static double median(List<Double> values) {
        var sorted = values.stream().sorted().toList();
        int size = sorted.size();
        return size % 2 == 1 ? sorted.get(size / 2) : (sorted.get(size / 2 - 1) + sorted.get(size / 2)) / 2;
    }

    private record Sample(String userId, long baseline, long assisted, boolean accepted) { }
}
