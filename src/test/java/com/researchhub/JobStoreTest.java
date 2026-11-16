package com.researchhub;

import com.researchhub.auth.AuthService;
import com.researchhub.common.ApiException;
import com.researchhub.common.Values;
import com.researchhub.jobs.JobStore;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.*;

@SpringBootTest(properties = {"spring.datasource.url=jdbc:h2:mem:hub-jobs;DB_CLOSE_DELAY=-1", "hub.jobs.enabled=false"})
class JobStoreTest {
    @Autowired JobStore jobs;
    @Autowired JdbcTemplate db;
    @Autowired AuthService auth;

    record Fixture(String userId, String workspace) { }

    Fixture fixture() {
        var user = auth.register(UUID.randomUUID() + "@example.test", "owner", "test-password-123").user();
        String workspace = Values.id();
        db.update("INSERT INTO workspaces(id,name,created_by,created_at) VALUES (?,?,?,?)", workspace, "jobs", user.id(), Values.now());
        db.update("INSERT INTO workspace_members(workspace_id,user_id,role) VALUES (?,?,'OWNER')", workspace, user.id());
        return new Fixture(user.id(), workspace);
    }

    JobStore.Job enqueue(Fixture f, String key) {
        var job = jobs.enqueue(f.workspace(), f.userId(), "MEETING_DRAFT", Values.id(), key, Map.of("test", true));
        // Avoid timestamp rounding at the availability boundary in immediate claim assertions.
        db.update("UPDATE jobs SET available_at=? WHERE id=?", Values.timestamp(Instant.now().minusSeconds(1)), job.id());
        return job;
    }

    @Test
    void idempotencyKeyIsBoundToTheRequest() {
        Fixture f = fixture();
        var original = enqueue(f, "same");
        var repeated = jobs.enqueue(f.workspace(), f.userId(), original.kind(), original.resourceId(), "same", Map.of("test", true));
        assertThat(repeated.id()).isEqualTo(original.id());
        assertThatThrownBy(() -> jobs.enqueue(f.workspace(), f.userId(), original.kind(), original.resourceId(), "same", Map.of("test", false)))
                .isInstanceOf(ApiException.class).hasMessageContaining("Idempotency-Key");
        jobs.cancel(original.id(), f.userId());
    }

    @Test
    void expiredLeaseCanBeReclaimedAndOldWorkerCannotPublish() {
        Fixture f = fixture();
        var original = enqueue(f, "reclaim");
        var first = jobs.claim().orElseThrow();
        assertThat(first.id()).isEqualTo(original.id());
        db.update("UPDATE jobs SET lease_until=? WHERE id=?", Values.timestamp(Instant.now().minusSeconds(1)), first.id());
        assertThat(jobs.renew(first)).isFalse();
        var second = jobs.claim().orElseThrow();
        assertThat(second.id()).isEqualTo(first.id());
        assertThat(second.leaseToken()).isNotEqualTo(first.leaseToken());
        AtomicBoolean oldPublished = new AtomicBoolean();
        assertThat(jobs.succeed(first, Map.of("result", "old"), () -> oldPublished.set(true))).isFalse();
        assertThat(oldPublished).isFalse();
        assertThat(jobs.succeed(second, Map.of("result", "new"), () -> { })).isTrue();
        assertThat(jobs.get(first.id()).result()).contains("new");
    }

    @Test
    void cancellingRunningJobFencesPublicationAndAllowsManualRetry() {
        Fixture f = fixture();
        var original = enqueue(f, "cancel");
        var execution = jobs.claim().orElseThrow();
        jobs.cancel(original.id(), f.userId());
        assertThat(jobs.succeed(execution, Map.of(), () -> fail("Cancelled job must not publish"))).isFalse();
        assertThat(jobs.retry(original.id(), f.userId()).status()).isEqualTo("QUEUED");
        assertThat(jobs.get(original.id()).attempts()).isZero();
        jobs.cancel(original.id(), f.userId());
    }

    @Test
    void crashOnLastAttemptConvergesToFailure() {
        Fixture f = fixture();
        var original = enqueue(f, "last");
        var execution = jobs.claim().orElseThrow();
        db.update("UPDATE jobs SET attempts=3,lease_until=? WHERE id=?", Values.timestamp(Instant.now().minusSeconds(1)), execution.id());
        assertThat(jobs.claim()).isEmpty();
        assertThat(jobs.get(original.id()).status()).isEqualTo("FAILED");
    }

    @Test
    void resultAndCompletionStateCommitAtomically() {
        Fixture f = fixture();
        var original = enqueue(f, "atomic");
        var execution = jobs.claim().orElseThrow();
        assertThatThrownBy(() -> jobs.succeed(execution, Map.of(), () -> {
            db.update("UPDATE workspaces SET name='should-rollback' WHERE id=?", f.workspace());
            throw new IllegalStateException("simulate failed result write");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(db.queryForObject("SELECT name FROM workspaces WHERE id=?", String.class, f.workspace())).isEqualTo("jobs");
        assertThat(jobs.get(original.id()).status()).isEqualTo("RUNNING");
        jobs.fail(execution, "fatal", false);
    }

    @Test
    void transientFailuresWaitBeforeRetrying() {
        Fixture f = fixture();
        var original = enqueue(f, "backoff");
        var execution = jobs.claim().orElseThrow();
        jobs.fail(execution, "transient model error", true);
        assertThat(jobs.get(original.id()).status()).isEqualTo("QUEUED");
        assertThat(jobs.claim()).isEmpty();
        db.update("UPDATE jobs SET available_at=? WHERE id=?", Values.timestamp(Instant.now().minusSeconds(1)), original.id());
        var retried = jobs.claim().orElseThrow();
        assertThat(retried.attempts()).isEqualTo(2);
        jobs.fail(retried, "fatal", false);
    }

    @Test
    void concurrentWorkersCanClaimAJobOnlyOnce() throws Exception {
        Fixture f = fixture();
        var original = enqueue(f, "concurrent");
        var executor = java.util.concurrent.Executors.newFixedThreadPool(8);
        try {
            var results = executor.invokeAll(java.util.stream.IntStream.range(0, 16)
                    .<java.util.concurrent.Callable<java.util.Optional<JobStore.Job>>>mapToObj(i -> jobs::claim).toList());
            int claimed = 0;
            JobStore.Job execution = null;
            for (var result : results) {
                var candidate = result.get();
                if (candidate.isPresent()) { claimed++; execution = candidate.get(); }
            }
            assertThat(claimed).isEqualTo(1);
            assertThat(execution.id()).isEqualTo(original.id());
            jobs.fail(execution, "done", false);
        } finally {
            executor.shutdownNow();
        }
    }
}
