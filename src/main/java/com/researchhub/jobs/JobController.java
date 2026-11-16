package com.researchhub.jobs;

import com.researchhub.auth.AuthInterceptor;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api")
public class JobController {
    private final JobStore jobs;

    public JobController(JobStore jobs) { this.jobs = jobs; }

    @GetMapping("/jobs/{id}")
    public JobStore.View get(@PathVariable String id, HttpServletRequest request) {
        return jobs.view(jobs.requireOwned(id, AuthInterceptor.userId(request)));
    }

    @GetMapping("/jobs/{id}/events")
    public List<JobStore.Event> events(@PathVariable String id, HttpServletRequest request) {
        return jobs.events(id, AuthInterceptor.userId(request));
    }

    @GetMapping("/workspaces/{workspaceId}/jobs")
    public List<JobStore.View> list(@PathVariable String workspaceId, HttpServletRequest request) {
        return jobs.list(workspaceId, AuthInterceptor.userId(request));
    }

    @PostMapping("/jobs/{id}/cancel")
    public JobStore.View cancel(@PathVariable String id, HttpServletRequest request) {
        return jobs.cancel(id, AuthInterceptor.userId(request));
    }

    @PostMapping("/jobs/{id}/retry")
    public JobStore.View retry(@PathVariable String id, HttpServletRequest request) {
        return jobs.retry(id, AuthInterceptor.userId(request));
    }
}
