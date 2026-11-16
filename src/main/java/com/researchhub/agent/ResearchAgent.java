package com.researchhub.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.researchhub.common.ApiException;
import com.researchhub.common.Values;
import com.researchhub.jobs.JobStore;
import com.researchhub.meeting.MeetingService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class ResearchAgent {
    public record Source(String id, String type, String resourceId, String title, Integer pageNumber,
                         Integer chunkIndex, String content) { }
    public record Citation(String sourceId, String quote) { }
    public record Point(String text, List<Citation> citations) { }
    public record Draft(String mode, String generatedAt, String coverage, List<Point> points, List<Source> sources,
                        String inputHash, Long promptTokens, Long completionTokens) { }
    public record ActionProposal(String title, String evidenceQuote) { }
    public record Proposals(String mode, int minutesVersion, List<ActionProposal> items, Long promptTokens, Long completionTokens) { }

    private static final int MAX_CONTEXT_CHARS = 35000;
    private final JdbcTemplate db;
    private final MeetingService meetings;
    private final JobStore jobs;
    private final LlmClient llm;
    private final ObjectMapper mapper;

    public ResearchAgent(JdbcTemplate db, MeetingService meetings, JobStore jobs, LlmClient llm, ObjectMapper mapper) {
        this.db = db;
        this.meetings = meetings;
        this.jobs = jobs;
        this.llm = llm;
        this.mapper = mapper;
    }

    public Draft draft(JobStore.Job job) {
        var meeting = meetings.get(job.workspaceId(), job.resourceId(), job.requestedBy());
        jobs.progress(job, "READ_RECORDS", "读取指定周期内的共享阅读记录、论文摘录和未完成待办");
        List<Source> candidates = new ArrayList<>();
        var start = meeting.periodStart().atStartOfDay();
        var end = meeting.periodEnd().plusDays(1).atStartOfDay();
        candidates.addAll(db.query("""
                SELECT n.id,n.document_id,n.content,n.reading_status,u.display_name,d.title FROM reading_notes n
                JOIN documents d ON d.id=n.document_id JOIN app_users u ON u.id=n.user_id
                WHERE d.workspace_id=? AND d.visibility='GROUP' AND n.visibility='GROUP'
                AND n.updated_at>=? AND n.updated_at<? ORDER BY n.updated_at DESC LIMIT 80
                """, (rs, n) -> new Source("note:" + rs.getString("id"), "NOTE", rs.getString("document_id"),
                rs.getString("display_name") + " / " + rs.getString("title") + " / " + rs.getString("reading_status"), null, null,
                rs.getString("content")), job.workspaceId(), start, end));
        candidates.addAll(db.query("""
                SELECT t.id,t.title,t.status,t.due_date,u.display_name FROM action_tasks t
                LEFT JOIN app_users u ON u.id=t.assignee_id WHERE t.workspace_id=? AND t.status IN ('TODO','IN_PROGRESS')
                AND t.created_at<? ORDER BY t.created_at DESC LIMIT 30
                """, (rs, n) -> new Source("task:" + rs.getString("id"), "TASK", rs.getString("id"), rs.getString("title"), null, null,
                "任务=" + rs.getString("title") + "；状态=" + rs.getString("status") + "；负责人="
                        + (rs.getString("display_name") == null ? "待确认" : rs.getString("display_name")) + "；截止日期="
                        + (rs.getDate("due_date") == null ? "待确认" : rs.getDate("due_date").toLocalDate())), job.workspaceId(), end));
        candidates.addAll(db.query("""
                SELECT d.id,d.title,c.page_number,c.chunk_index,c.content FROM documents d
                JOIN document_chunks c ON c.document_id=d.id
                WHERE d.workspace_id=? AND d.visibility='GROUP' AND d.status='READY' AND c.chunk_index<4
                AND ((d.created_at>=? AND d.created_at<?) OR EXISTS
                    (SELECT 1 FROM reading_notes n WHERE n.document_id=d.id AND n.visibility='GROUP'
                     AND n.updated_at>=? AND n.updated_at<?))
                ORDER BY d.created_at DESC,c.chunk_index LIMIT 100
                """, (rs, n) -> new Source("doc:" + rs.getString("id") + ":" + rs.getInt("chunk_index"), "DOCUMENT", rs.getString("id"),
                rs.getString("title"), rs.getInt("page_number"), rs.getInt("chunk_index"), rs.getString("content")),
                job.workspaceId(), start, end, start, end));
        List<Source> sources = new ArrayList<>();
        int characters = 0;
        for (Source source : candidates) {
            if (source.content().isBlank()) continue;
            if (characters + source.content().length() > MAX_CONTEXT_CHARS || sources.size() >= 60) continue;
            sources.add(source);
            characters += source.content().length();
        }
        if (sources.isEmpty()) throw ApiException.badRequest("该周期没有共享阅读记录、就绪资料或未完成待办，请先导入并共享资料");
        jobs.progress(job, "BUILD_CONTEXT", "已准备 " + sources.size() + " 条有来源的记录，私有资料已过滤");
        List<Point> points;
        Long promptTokens = null;
        Long completionTokens = null;
        if ("local".equals(llm.mode())) {
            points = sources.stream().limit(12).map(source -> {
                String quote = excerpt(source.content(), 360);
                return new Point(source.title() + "\n" + quote, List.of(new Citation(source.id(), quote)));
            }).toList();
        } else {
            jobs.progress(job, "MODEL", "请求模型整理草稿，输出将经过引用核验");
            var reply = llm.generate("返回格式 {\"points\":[{\"text\":\"汇报要点\",\"citations\":[{\"sourceId\":\"来源 id\",\"quote\":\"来源 content 中逐字连续的原文\"}]}]}。"
                            + "生成 1 到 12 条要点，每条最多 1200 字符且必须有 1 到 3 个引用。"
                            + "quote 必须逐字复制、长度 4 到 500 字符。区分阅读记录、论文内容和待办，论文结果不得表述为用户自己的实验成果。",
                    Map.of("meeting", meeting.title(), "periodStart", meeting.periodStart(), "periodEnd", meeting.periodEnd(), "sources", sources));
            points = validatePoints(reply.data(), sources);
            promptTokens = reply.promptTokens();
            completionTokens = reply.completionTokens();
        }
        jobs.progress(job, "VERIFY_CITATIONS", "已核对引用来源和逐字摘录，草稿仍需人工审核");
        String coverage = "UTC 周期 " + meeting.periodStart() + " 至 " + meeting.periodEnd()
                + "；使用 " + sources.size() + " 条共享来源；论文最多取前 4 个片段；上下文最多 35000 字符。";
        return new Draft(llm.mode(), java.time.Instant.now().toString(), coverage, points, List.copyOf(sources),
                Values.hash(Values.json(sources)), promptTokens, completionTokens);
    }

    public void saveDraft(JobStore.Job job, Draft draft) {
        meetings.requireEditor(job.workspaceId(), job.resourceId(), job.requestedBy());
        db.update("UPDATE meetings SET draft_json=? WHERE id=?", Values.json(draft), job.resourceId());
    }

    public Proposals actions(JobStore.Job job) {
        var meeting = meetings.get(job.workspaceId(), job.resourceId(), job.requestedBy());
        int version = expectedVersion(job);
        if (meeting.minutesVersion() != version) throw ApiException.conflict("纪要已变化，请创建新的提取任务");
        jobs.progress(job, "READ_MINUTES", "读取纪要第 " + version + " 版，提取待确认事项");
        List<ActionProposal> items;
        Long promptTokens = null;
        Long completionTokens = null;
        if ("local".equals(llm.mode())) {
            items = meeting.minutes().lines().map(String::strip)
                    .filter(line -> line.startsWith("TODO:") || line.startsWith("TODO：") || line.startsWith("待办：")
                            || line.startsWith("待办:") || line.startsWith("- [ ]"))
                    .filter(line -> line.length() >= 5).limit(30)
                    .map(line -> new ActionProposal(excerpt(line.replaceFirst("^(TODO[:：]|待办[:：]|- \\[ \\])\\s*", ""), 300),
                            excerpt(line, 500))).filter(item -> !item.title().isBlank()).toList();
        } else {
            jobs.progress(job, "MODEL", "请求模型提取待办，并保留纪要中的原文证据");
            var reply = llm.generate("返回 {\"items\":[{\"title\":\"待办标题\",\"evidenceQuote\":\"纪要中的逐字原文\"}]}。"
                            + "只提取明确需要后续执行的事项，最多 30 项。title 长度 1 到 300，evidenceQuote 长度 4 到 500。"
                            + "没有明确事项则返回空数组。不得推测负责人和截止时间。",
                    Map.of("meetingTitle", meeting.title(), "minutes", meeting.minutes()));
            items = validateActions(reply.data(), meeting.minutes());
            promptTokens = reply.promptTokens();
            completionTokens = reply.completionTokens();
        }
        jobs.progress(job, "VERIFY_ACTIONS", "已核对 " + items.size() + " 项提议的纪要原文，等待用户确认");
        return new Proposals(llm.mode(), version, items, promptTokens, completionTokens);
    }

    public void saveActions(JobStore.Job job, Proposals proposals) {
        meetings.requireEditor(job.workspaceId(), job.resourceId(), job.requestedBy());
        int changed = db.update("""
                UPDATE meetings SET proposals_json=?,proposals_version=? WHERE id=? AND minutes_version=? AND proposals_json IS NULL
                """, Values.json(proposals), proposals.minutesVersion(), job.resourceId(), proposals.minutesVersion());
        if (changed == 0) throw ApiException.conflict("纪要已更新或该版本已有提议，请刷新后重新处理");
    }

    private List<Point> validatePoints(JsonNode data, List<Source> sources) {
        JsonNode points = data.get("points");
        if (points == null || !points.isArray() || points.isEmpty() || points.size() > 12) throw invalid("草稿要点格式不合法");
        Map<String, Source> index = new LinkedHashMap<>();
        sources.forEach(source -> index.put(source.id(), source));
        List<Point> out = new ArrayList<>();
        for (JsonNode point : points) {
            String text = requiredText(point, "text", 1, 1200);
            JsonNode refs = point.get("citations");
            if (refs == null || !refs.isArray() || refs.isEmpty() || refs.size() > 3) throw invalid("草稿要点必须包含引用");
            List<Citation> citations = new ArrayList<>();
            for (JsonNode ref : refs) {
                String sourceId = requiredText(ref, "sourceId", 1, 100);
                String quote = requiredText(ref, "quote", 4, 500);
                Source source = index.get(sourceId);
                if (source == null || !source.content().contains(quote)) throw invalid("引用无法在给定来源中定位");
                citations.add(new Citation(sourceId, quote));
            }
            out.add(new Point(text, List.copyOf(citations)));
        }
        return List.copyOf(out);
    }

    private List<ActionProposal> validateActions(JsonNode data, String minutes) {
        JsonNode items = data.get("items");
        if (items == null || !items.isArray() || items.size() > 30) throw invalid("待办提议格式不合法");
        List<ActionProposal> out = new ArrayList<>();
        for (JsonNode item : items) {
            String title = requiredText(item, "title", 1, 300);
            String quote = requiredText(item, "evidenceQuote", 4, 500);
            if (!minutes.contains(quote)) throw invalid("待办提议缺少可定位的纪要原文");
            out.add(new ActionProposal(title, quote));
        }
        return List.copyOf(out);
    }

    private int expectedVersion(JobStore.Job job) {
        try { return mapper.readTree(job.payload()).path("minutesVersion").asInt(); }
        catch (Exception e) { throw new IllegalStateException("Invalid action job payload", e); }
    }

    private static String requiredText(JsonNode node, String field, int min, int max) {
        JsonNode value = node.get(field);
        if (value == null || !value.isTextual() || value.asText().isBlank() || value.asText().length() < min || value.asText().length() > max)
            throw invalid("模型字段 " + field + " 不合法");
        return value.asText();
    }

    private static LlmClient.GenerationException invalid(String message) {
        return new LlmClient.GenerationException(message, true);
    }

    private static String excerpt(String text, int max) {
        int end = Math.min(text.length(), max);
        if (end < text.length() && Character.isHighSurrogate(text.charAt(end - 1))) end--;
        return text.substring(0, end);
    }
}
