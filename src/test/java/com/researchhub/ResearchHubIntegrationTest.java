package com.researchhub;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.researchhub.auth.AuthService;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.io.ByteArrayOutputStream;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

@SpringBootTest(properties = {"spring.datasource.url=jdbc:h2:mem:hub-it;DB_CLOSE_DELAY=-1", "hub.jobs.poll-ms=100"})
@AutoConfigureMockMvc
class ResearchHubIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired AuthService auth;

    record Fixture(String userId, String token, String workspace) {
        String base() { return "/api/workspaces/" + workspace; }
    }

    Fixture fixture() throws Exception {
        var login = account();
        JsonNode workspace = call("POST", "/api/workspaces", login.token(), Map.of("name", "测试课题组"), 201);
        return new Fixture(login.user().id(), login.token(), workspace.path("id").asText());
    }

    AuthService.Login account() {
        return auth.register(UUID.randomUUID() + "@example.test", "研究生", "test-password-123");
    }

    JsonNode call(String method, String path, String token, Object body, int status) throws Exception {
        return call(method, path, token, body, status, null);
    }

    JsonNode call(String method, String path, String token, Object body, int status, String key) throws Exception {
        var builder = switch (method) {
            case "POST" -> post(path);
            case "PUT" -> put(path);
            case "PATCH" -> patch(path);
            default -> get(path);
        };
        if (token != null) builder.header("Authorization", "Bearer " + token);
        if (key != null) builder.header("Idempotency-Key", key);
        if (body != null) builder.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(body));
        MvcResult result = mvc.perform(builder).andReturn();
        assertThat(result.getResponse().getStatus()).as(result.getResponse().getContentAsString()).isEqualTo(status);
        String content = result.getResponse().getContentAsString();
        return content.isBlank() ? json.nullNode() : json.readTree(content);
    }

    JsonNode importText(Fixture f, String title, String content, String visibility) throws Exception {
        JsonNode imported = call("POST", f.base() + "/documents/text", f.token(),
                Map.of("title", title, "content", content, "visibility", visibility), 202);
        awaitJob(f.token(), imported.path("job").path("id").asText(), "SUCCEEDED");
        return imported;
    }

    JsonNode awaitJob(String token, String id, String expected) {
        JsonNode[] result = new JsonNode[1];
        await().atMost(java.time.Duration.ofSeconds(15)).untilAsserted(() -> {
            result[0] = call("GET", "/api/jobs/" + id, token, null, 200);
            assertThat(result[0].path("status").asText()).as(result[0].toString()).isEqualTo(expected);
        });
        return result[0];
    }

    JsonNode createMeeting(Fixture f) throws Exception {
        String today = LocalDate.now(ZoneOffset.UTC).toString();
        return call("POST", f.base() + "/meetings", f.token(), Map.of("title", "周组会", "meetingDate", today,
                "periodStart", today, "periodEnd", today), 201);
    }

    @Test
    void authenticationAndLogout() throws Exception {
        String email = UUID.randomUUID() + "@example.test";
        JsonNode registration = call("POST", "/api/auth/register", null,
                Map.of("email", email, "displayName", "小李", "password", "test-password-123"), 201);
        String token = registration.path("token").asText();
        call("GET", "/api/auth/me", null, null, 401);
        call("POST", "/api/auth/login", null, Map.of("email", email, "password", "wrong-password"), 401);
        call("POST", "/api/auth/register", null,
                Map.of("email", email, "displayName", "小李", "password", "test-password-123"), 409);
        assertThat(call("GET", "/api/auth/me", token, null, 200).path("displayName").asText()).isEqualTo("小李");
        call("POST", "/api/auth/logout", token, null, 204);
        call("GET", "/api/auth/me", token, null, 401);
        JsonNode login = call("POST", "/api/auth/login", null, Map.of("email", email, "password", "test-password-123"), 200);
        call("GET", "/api/auth/me", login.path("token").asText(), null, 200);
    }

    @Test
    void permissionChecksCoverDocumentsSearchNotesAndJobs() throws Exception {
        Fixture f = fixture();
        var member = account();
        var stranger = account();
        call("POST", f.base() + "/members", f.token(), Map.of("email", member.user().email()), 200);
        JsonNode imported = importText(f, "PrivateMaterial", "PrivateNeedle secret experiment", "PRIVATE");
        String documentId = imported.path("document").path("id").asText();
        call("GET", f.base() + "/documents/" + documentId, member.token(), null, 404);
        assertThat(call("GET", f.base() + "/documents", member.token(), null, 200)).isEmpty();
        assertThat(call("GET", f.base() + "/search?q=PrivateNeedle", member.token(), null, 200)).isEmpty();
        call("GET", f.base() + "/documents/" + documentId + "/chunks", stranger.token(), null, 404);
        call("GET", "/api/jobs/" + imported.path("job").path("id").asText(), member.token(), null, 404);
        call("POST", f.base() + "/members", member.token(), Map.of("email", stranger.user().email()), 404);
        call("PUT", f.base() + "/documents/" + documentId + "/notes/mine", f.token(),
                Map.of("readingStatus", "READING", "visibility", "GROUP", "content", "secret", "version", 0), 400);
    }

    @Test
    void textImportIsDeduplicatedAndSearchSupportsChineseAndCase() throws Exception {
        Fixture f = fixture();
        JsonNode first = importText(f, "检索增强生成", "这里讨论检索增强生成，以及 Retrieval Augmented Generation。", "GROUP");
        JsonNode second = call("POST", f.base() + "/documents/text", f.token(), Map.of("title", "另一个标题",
                "content", "这里讨论检索增强生成，以及 Retrieval Augmented Generation。", "visibility", "GROUP"), 202);
        assertThat(second.path("duplicate").asBoolean()).isTrue();
        assertThat(second.path("document").path("id")).isEqualTo(first.path("document").path("id"));
        assertThat(call("GET", f.base() + "/search?q=检索增强", f.token(), null, 200)).isNotEmpty();
        assertThat(call("GET", f.base() + "/search?q=RETRIEVAL", f.token(), null, 200)).isNotEmpty();
    }

    @Test
    void readingUpdatesRequireCurrentVersion() throws Exception {
        Fixture f = fixture();
        String document = importText(f, "论文", "论文内容", "GROUP").path("document").path("id").asText();
        String endpoint = f.base() + "/documents/" + document + "/notes/mine";
        JsonNode saved = call("PUT", endpoint, f.token(), Map.of("readingStatus", "READING", "visibility", "PRIVATE",
                "content", "初读", "version", 0), 200);
        assertThat(saved.path("version").asInt()).isEqualTo(1);
        call("PUT", endpoint, f.token(), Map.of("readingStatus", "DONE", "visibility", "GROUP", "content", "完成阅读", "version", 1), 200);
        call("PUT", endpoint, f.token(), Map.of("readingStatus", "BLOCKED", "visibility", "GROUP", "content", "旧版本覆盖", "version", 1), 409);
    }

    @Test
    void meetingDraftContainsVerifiableSourcesAndExcludesPrivateNotes() throws Exception {
        Fixture f = fixture();
        String document = importText(f, "共享论文", "公开论文说明检索流程与引用核验。", "GROUP").path("document").path("id").asText();
        call("PUT", f.base() + "/documents/" + document + "/notes/mine", f.token(), Map.of("readingStatus", "READING",
                "visibility", "PRIVATE", "content", "SECRET_PRIVATE_NOTE", "version", 0), 200);
        importText(f, "Private paper", "SECRET_PRIVATE_DOCUMENT", "PRIVATE");
        String meeting = createMeeting(f).path("id").asText();
        JsonNode job = call("POST", f.base() + "/meetings/" + meeting + "/draft-jobs", f.token(), null, 202, "draft-1");
        JsonNode finished = awaitJob(f.token(), job.path("id").asText(), "SUCCEEDED");
        JsonNode draft = finished.path("result");
        assertThat(draft.path("mode").asText()).isEqualTo("local");
        assertThat(draft.toString()).doesNotContain("SECRET_PRIVATE_NOTE", "SECRET_PRIVATE_DOCUMENT");
        for (JsonNode point : draft.path("points")) {
            for (JsonNode citation : point.path("citations")) {
                JsonNode source = java.util.stream.StreamSupport.stream(draft.path("sources").spliterator(), false)
                        .filter(s -> s.path("id").asText().equals(citation.path("sourceId").asText())).findFirst().orElseThrow();
                assertThat(source.path("content").asText()).contains(citation.path("quote").asText());
            }
        }
        JsonNode repeated = call("POST", f.base() + "/meetings/" + meeting + "/draft-jobs", f.token(), null, 202, "draft-1");
        assertThat(repeated.path("id")).isEqualTo(job.path("id"));
        assertThat(call("GET", "/api/jobs/" + job.path("id").asText() + "/events", f.token(), null, 200).size()).isGreaterThan(3);
        assertThat(call("GET", f.base() + "/meetings/" + meeting, f.token(), null, 200).path("draft")).isEqualTo(draft);
    }

    @Test
    void confirmedTasksAreIdempotentAndHaveVersionedTransitions() throws Exception {
        Fixture f = fixture();
        String meeting = createMeeting(f).path("id").asText();
        String meetingPath = f.base() + "/meetings/" + meeting;
        call("PUT", meetingPath + "/minutes", f.token(), Map.of("content", "讨论了本周阅读。\nTODO: 复现基线实验\n待办：核对数据集划分", "version", 0), 200);
        JsonNode job = call("POST", meetingPath + "/action-jobs", f.token(), null, 202, "actions-1");
        JsonNode completed = awaitJob(f.token(), job.path("id").asText(), "SUCCEEDED");
        assertThat(completed.path("result").path("items")).hasSize(2);
        assertThat(call("GET", f.base() + "/tasks", f.token(), null, 200)).isEmpty();
        var confirmation = Map.of("proposalVersion", 1, "items", List.of(Map.of("index", 0, "title", "复现基线实验", "assigneeId", f.userId())));
        JsonNode tasks = call("POST", meetingPath + "/confirm-actions", f.token(), confirmation, 200);
        JsonNode repeated = call("POST", meetingPath + "/confirm-actions", f.token(), confirmation, 200);
        assertThat(repeated.path(0).path("id")).isEqualTo(tasks.path(0).path("id"));
        String id = tasks.path(0).path("id").asText();
        call("PATCH", f.base() + "/tasks/" + id, f.token(), Map.of("status", "DONE", "version", 1), 200);
        call("PATCH", f.base() + "/tasks/" + id, f.token(), Map.of("status", "TODO", "version", 1), 409);
        call("PATCH", f.base() + "/tasks/" + id, f.token(), Map.of("status", "TODO", "version", 2), 200);
        call("POST", meetingPath + "/confirm-actions", f.token(), Map.of("proposalVersion", 1,
                "items", List.of(Map.of("index", 0, "title", "不同的确认内容"))), 409);
        call("PUT", meetingPath + "/minutes", f.token(), Map.of("content", "TODO: 新的待办", "version", 1), 200);
        call("POST", meetingPath + "/confirm-actions", f.token(), confirmation, 409);
        call("POST", meetingPath + "/action-jobs", f.token(), null, 409, "actions-1");
    }

    @Test
    void rejectsNonMemberAssigneesAndInvalidMeetingDates() throws Exception {
        Fixture f = fixture();
        var outsider = account();
        call("POST", f.base() + "/meetings", f.token(), Map.of("title", "bad", "meetingDate", "2026-01-02",
                "periodStart", "2026-01-02", "periodEnd", "2026-01-01"), 400);
        String meetingPath = f.base() + "/meetings/" + createMeeting(f).path("id").asText();
        call("PUT", meetingPath + "/minutes", f.token(), Map.of("content", "TODO: 检查引用", "version", 0), 200);
        JsonNode job = call("POST", meetingPath + "/action-jobs", f.token(), null, 202, "x");
        awaitJob(f.token(), job.path("id").asText(), "SUCCEEDED");
        call("POST", meetingPath + "/confirm-actions", f.token(), Map.of("proposalVersion", 1,
                "items", List.of(Map.of("index", 0, "title", "检查引用", "assigneeId", outsider.user().id()))), 404);
    }

    @Test
    void realPdfIsParsedWithPageReferencesAndInvalidPdfFails() throws Exception {
        Fixture f = fixture();
        byte[] bytes;
        try (var pdf = new PDDocument(); var output = new ByteArrayOutputStream()) {
            var page = new PDPage();
            pdf.addPage(page);
            try (var stream = new PDPageContentStream(pdf, page)) {
                stream.beginText();
                stream.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                stream.newLineAtOffset(30, 700);
                stream.showText("Research evidence on page one.");
                stream.endText();
            }
            pdf.save(output);
            bytes = output.toByteArray();
        }
        var result = mvc.perform(multipart(f.base() + "/documents").file(new MockMultipartFile("file", "paper.pdf", "application/pdf", bytes))
                .param("title", "PDF paper").param("visibility", "GROUP").header("Authorization", "Bearer " + f.token())).andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(202);
        JsonNode imported = json.readTree(result.getResponse().getContentAsByteArray());
        awaitJob(f.token(), imported.path("job").path("id").asText(), "SUCCEEDED");
        JsonNode chunks = call("GET", f.base() + "/documents/" + imported.path("document").path("id").asText() + "/chunks", f.token(), null, 200);
        assertThat(chunks.path(0).path("pageNumber").asInt()).isEqualTo(1);
        assertThat(chunks.path(0).path("content").asText()).contains("Research evidence");
        var bad = mvc.perform(multipart(f.base() + "/documents").file(new MockMultipartFile("file", "bad.pdf", "application/pdf", "%PDF-broken".getBytes()))
                .param("title", "broken").header("Authorization", "Bearer " + f.token())).andReturn();
        JsonNode badImport = json.readTree(bad.getResponse().getContentAsByteArray());
        JsonNode failed = awaitJob(f.token(), badImport.path("job").path("id").asText(), "FAILED");
        assertThat(failed.path("attempts").asInt()).isEqualTo(1);
    }

    @Test
    void measurementsIncludeReviewTimeAndFailures() throws Exception {
        Fixture f = fixture();
        call("POST", f.base() + "/measurements", f.token(), Map.of("scenario", "MEETING_PREPARATION", "baselineMs", 1000,
                "assistedMs", 200, "reviewMs", 300, "qualityAccepted", true, "notes", "first"), 201);
        call("POST", f.base() + "/measurements", f.token(), Map.of("scenario", "MEETING_PREPARATION", "baselineMs", 1000,
                "assistedMs", 1000, "reviewMs", 500, "qualityAccepted", false, "notes", "failed quality"), 201);
        JsonNode summary = call("GET", f.base() + "/measurements", f.token(), null, 200).path(0);
        assertThat(summary.path("samples").asInt()).isEqualTo(2);
        assertThat(summary.path("medianAssistedIncludingReviewMs").asDouble()).isEqualTo(1000);
        assertThat(summary.path("medianReductionPercent").asDouble()).isZero();
        assertThat(summary.path("acceptanceRate").asDouble()).isEqualTo(0.5);
    }

    @Test
    void swaggerAndHealthAreAvailable() throws Exception {
        assertThat(mvc.perform(get("/v3/api-docs")).andReturn().getResponse().getStatus()).isEqualTo(200);
        assertThat(mvc.perform(get("/actuator/health")).andReturn().getResponse().getStatus()).isEqualTo(200);
        call("GET", "/actuator/prometheus", null, null, 401);
    }

    @Test
    void browserEntryAndAuthenticatedCapabilitiesAreAvailable() throws Exception {
        var index = mvc.perform(get("/")).andReturn().getResponse();
        assertThat(index.getStatus()).isEqualTo(200);
        assertThat(index.getForwardedUrl()).isEqualTo("index.html");
        var page = mvc.perform(get("/index.html")).andReturn().getResponse();
        assertThat(page.getStatus()).isEqualTo(200);
        assertThat(page.getContentAsString()).contains("Research Hub", "/app.js", "/styles.css");
        assertThat(mvc.perform(get("/app.js")).andReturn().getResponse().getStatus()).isEqualTo(200);
        assertThat(mvc.perform(get("/styles.css")).andReturn().getResponse().getStatus()).isEqualTo(200);
        call("GET", "/api/system", null, null, 401);
        var session = account();
        var configuration = call("GET", "/api/system", session.token(), null, 200);
        assertThat(configuration.path("generationMode").asText()).isEqualTo("local");
        assertThat(configuration.path("maxUploadMb").asInt()).isEqualTo(8);
        assertThat(configuration.has("apiKey")).isFalse();
        assertThat(configuration.has("baseUrl")).isFalse();
    }
}
