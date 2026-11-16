package com.researchhub;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.researchhub.agent.LlmClient;
import com.researchhub.agent.ResearchAgent;
import com.researchhub.common.ApiException;
import com.researchhub.jobs.JobStore;
import com.researchhub.meeting.MeetingService;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ResearchAgentValidationTest {
    private final ObjectMapper json = new ObjectMapper();
    private final MeetingService meetings = mock(MeetingService.class);
    private final JobStore jobs = mock(JobStore.class);
    private final LlmClient llm = mock(LlmClient.class);
    private final JobStore.Job job = new JobStore.Job("job", "workspace", "user", "MINUTE_ACTIONS", "meeting", "RUNNING", 1,
            "lease", "{\"minutesVersion\":1}", null, null, LocalDateTime.now(), LocalDateTime.now());

    private ResearchAgent agent(JdbcTemplate db) {
        when(llm.mode()).thenReturn("openai");
        when(meetings.get("workspace", "meeting", "user")).thenReturn(new MeetingService.Meeting("meeting", "周组会",
                LocalDate.now(), LocalDate.now(), LocalDate.now(), null, "待办：检查原文引用", 1, null, 0, LocalDateTime.now(), "user"));
        return new ResearchAgent(db, meetings, jobs, llm, json);
    }

    @Test
    void actionModelMustReturnVerbatimEvidence() throws Exception {
        var agent = agent(mock(JdbcTemplate.class));
        when(llm.generate(anyString(), any())).thenReturn(new LlmClient.Reply(json.readTree(
                "{\"items\":[{\"title\":\"伪造任务\",\"evidenceQuote\":\"并不存在的讨论内容\"}]}"), 1L, 1L));
        assertThatThrownBy(() -> agent.actions(job)).isInstanceOf(LlmClient.GenerationException.class).hasMessageContaining("原文");
    }

    @Test
    void validActionRemainsAProposalWithoutInferredAssigneeOrDate() throws Exception {
        var agent = agent(mock(JdbcTemplate.class));
        when(llm.generate(anyString(), any())).thenReturn(new LlmClient.Reply(json.readTree(
                "{\"items\":[{\"title\":\"检查原文引用\",\"evidenceQuote\":\"检查原文引用\"}]}"), 10L, 3L));
        var result = agent.actions(job);
        assertThat(result.items()).hasSize(1);
        assertThat(result.items().get(0).evidenceQuote()).isEqualTo("检查原文引用");
        assertThat(result.minutesVersion()).isEqualTo(1);
    }

    @Test
    void oldMinuteVersionIsRejectedBeforeModelCall() {
        var agent = agent(mock(JdbcTemplate.class));
        var stale = new JobStore.Job("job", "workspace", "user", "MINUTE_ACTIONS", "meeting", "RUNNING", 1,
                "lease", "{\"minutesVersion\":0}", null, null, LocalDateTime.now(), LocalDateTime.now());
        assertThatThrownBy(() -> agent.actions(stale)).isInstanceOf(ApiException.class);
        verify(llm, never()).generate(anyString(), any());
    }

    private JdbcTemplate sourceDatabase() {
        return mock(JdbcTemplate.class, invocation -> {
            if (invocation.getMethod().getName().equals("query")) {
                String sql = invocation.getArgument(0);
                if (sql.contains("SELECT n.id,n.document_id")) return List.of(new ResearchAgent.Source("note:1", "NOTE", "document",
                        "阅读记录", null, null, "本文使用公开数据集验证了引用准确率。"));
                return List.of();
            }
            return RETURNS_DEFAULTS.answer(invocation);
        });
    }

    @Test
    void draftRejectsUnknownSourcesAndAlteredQuotations() throws Exception {
        var agent = agent(sourceDatabase());
        when(llm.generate(anyString(), any())).thenReturn(new LlmClient.Reply(json.readTree(
                "{\"points\":[{\"text\":\"测试\",\"citations\":[{\"sourceId\":\"made-up\",\"quote\":\"公开数据集\"}]}]}"), null, null));
        assertThatThrownBy(() -> agent.draft(job)).isInstanceOf(LlmClient.GenerationException.class);
        when(llm.generate(anyString(), any())).thenReturn(new LlmClient.Reply(json.readTree(
                "{\"points\":[{\"text\":\"测试\",\"citations\":[{\"sourceId\":\"note:1\",\"quote\":\"编造的引用\"}]}]}"), null, null));
        assertThatThrownBy(() -> agent.draft(job)).isInstanceOf(LlmClient.GenerationException.class);
    }

    @Test
    void validDraftKeepsTheEvidenceSnapshotAndTokenUsage() throws Exception {
        var agent = agent(sourceDatabase());
        when(llm.generate(anyString(), any())).thenReturn(new LlmClient.Reply(json.readTree(
                "{\"points\":[{\"text\":\"阅读记录涉及引用评测\",\"citations\":[{\"sourceId\":\"note:1\",\"quote\":\"公开数据集验证了引用准确率\"}]}]}"), 20L, 5L));
        var draft = agent.draft(job);
        assertThat(draft.sources()).hasSize(1);
        assertThat(draft.points()).hasSize(1);
        assertThat(draft.inputHash()).hasSize(64);
        assertThat(draft.promptTokens()).isEqualTo(20L);
    }
}
