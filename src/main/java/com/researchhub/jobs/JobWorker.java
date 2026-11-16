package com.researchhub.jobs;

import com.researchhub.HubProperties;
import com.researchhub.agent.LlmClient;
import com.researchhub.agent.ResearchAgent;
import com.researchhub.common.ApiException;
import com.researchhub.document.DocumentParser;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

@Component
@ConditionalOnProperty(prefix = "hub.jobs", name = "enabled", havingValue = "true", matchIfMissing = true)
public class JobWorker {
    private static final Logger log = LoggerFactory.getLogger(JobWorker.class);
    private final JobStore jobs;
    private final DocumentParser parser;
    private final ResearchAgent agent;
    private final MeterRegistry metrics;
    private final int concurrency;
    private final ExecutorService executor;
    private final Map<String, JobStore.Job> active = new ConcurrentHashMap<>();
    private final Map<String, Thread> runningThreads = new ConcurrentHashMap<>();
    private volatile boolean stopping;

    public JobWorker(JobStore jobs, DocumentParser parser, ResearchAgent agent, MeterRegistry metrics, HubProperties properties) {
        this.jobs = jobs;
        this.parser = parser;
        this.agent = agent;
        this.metrics = metrics;
        this.concurrency = properties.jobs().concurrency();
        this.executor = Executors.newFixedThreadPool(concurrency, runnable -> {
            Thread thread = new Thread(runnable, "research-worker");
            thread.setDaemon(true);
            return thread;
        });
        metrics.gauge("research.jobs.active", active, Map::size);
    }

    @Scheduled(fixedDelayString = "${hub.jobs.poll-ms:500}")
    public void poll() {
        if (stopping) return;
        try {
            while (active.size() < concurrency) {
                var next = jobs.claim();
                if (next.isEmpty()) break;
                JobStore.Job job = next.get();
                active.put(job.leaseToken(), job);
                executor.submit(() -> execute(job));
            }
        } catch (Exception e) {
            log.error("Failed to poll persistent job queue", e);
        }
    }

    @Scheduled(fixedDelay = 5000)
    public void heartbeat() {
        for (var job : active.values()) {
            try {
                if (!jobs.renew(job)) {
                    Thread thread = runningThreads.get(job.leaseToken());
                    if (thread != null) thread.interrupt();
                }
            }
            catch (Exception e) { log.warn("Lease renewal failed for job {}", job.id(), e); }
        }
    }

    private void execute(JobStore.Job job) {
        long start = System.nanoTime();
        String outcome = "failed";
        runningThreads.put(job.leaseToken(), Thread.currentThread());
        try {
            if (!jobs.renew(job)) {
                outcome = "discarded";
                return;
            }
            boolean published;
            switch (job.kind()) {
                case "IMPORT" -> {
                    jobs.progress(job, "PARSE", "解析原始文件并按页保留正文片段");
                    var parsed = parser.parse(job.resourceId());
                    published = jobs.succeed(job, Map.of("documentId", job.resourceId(), "pages", parsed.pageCount(),
                            "chunks", parsed.chunks().size()), () -> parser.persist(job.resourceId(), parsed));
                }
                case "MEETING_DRAFT" -> {
                    var draft = agent.draft(job);
                    published = jobs.succeed(job, draft, () -> agent.saveDraft(job, draft));
                }
                case "MINUTE_ACTIONS" -> {
                    var proposals = agent.actions(job);
                    published = jobs.succeed(job, proposals, () -> agent.saveActions(job, proposals));
                }
                default -> throw new IllegalStateException("Unknown job kind " + job.kind());
            }
            outcome = published ? "succeeded" : "discarded";
        } catch (Exception e) {
            boolean retryable = !(e instanceof ApiException)
                    && (!(e instanceof LlmClient.GenerationException generation) || generation.retryable());
            String message = e instanceof ApiException || e instanceof LlmClient.GenerationException
                    ? e.getMessage() : "内部处理失败，系统将按重试策略恢复";
            log.warn("Job {} ({}) failed", job.id(), job.kind(), e);
            try { jobs.fail(job, message, retryable); }
            catch (Exception persistenceError) { log.error("Failed to record job failure {}", job.id(), persistenceError); }
        } finally {
            metrics.timer("research.jobs.duration", "kind", job.kind(), "outcome", outcome)
                    .record(System.nanoTime() - start, TimeUnit.NANOSECONDS);
            runningThreads.remove(job.leaseToken());
            active.remove(job.leaseToken(), job);
        }
    }

    @PreDestroy
    public void close() {
        stopping = true;
        executor.shutdown();
        try {
            if (!executor.awaitTermination(15, TimeUnit.SECONDS)) executor.shutdownNow();
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
