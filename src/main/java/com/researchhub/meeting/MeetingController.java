package com.researchhub.meeting;

import com.researchhub.auth.AuthInterceptor;
import com.researchhub.jobs.JobStore;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/workspaces/{workspaceId}/meetings")
public class MeetingController {
    public record Create(@NotBlank @Size(max = 240) String title, @NotNull LocalDate meetingDate,
                         @NotNull LocalDate periodStart, @NotNull LocalDate periodEnd) { }
    public record Minutes(@NotBlank @Size(max = 50000) String content, @NotNull @Min(0) Integer version) { }
    private final MeetingService meetings;

    public MeetingController(MeetingService meetings) { this.meetings = meetings; }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public MeetingService.Meeting create(@PathVariable String workspaceId, @Valid @RequestBody Create body, HttpServletRequest request) {
        return meetings.create(workspaceId, AuthInterceptor.userId(request), body.title(), body.meetingDate(), body.periodStart(), body.periodEnd());
    }

    @GetMapping
    public List<MeetingService.Meeting> list(@PathVariable String workspaceId, HttpServletRequest request) {
        return meetings.list(workspaceId, AuthInterceptor.userId(request));
    }

    @GetMapping("/{id}")
    public MeetingService.Meeting get(@PathVariable String workspaceId, @PathVariable String id, HttpServletRequest request) {
        return meetings.get(workspaceId, id, AuthInterceptor.userId(request));
    }

    @PutMapping("/{id}/minutes")
    public MeetingService.Meeting minutes(@PathVariable String workspaceId, @PathVariable String id,
                                          @Valid @RequestBody Minutes body, HttpServletRequest request) {
        return meetings.saveMinutes(workspaceId, id, AuthInterceptor.userId(request), body.content(), body.version());
    }

    @PostMapping("/{id}/draft-jobs")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public JobStore.View draft(@PathVariable String workspaceId, @PathVariable String id,
                              @RequestHeader("Idempotency-Key") String key, HttpServletRequest request) {
        return meetings.enqueue(workspaceId, id, AuthInterceptor.userId(request), "MEETING_DRAFT", key);
    }

    @PostMapping("/{id}/action-jobs")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public JobStore.View actions(@PathVariable String workspaceId, @PathVariable String id,
                                @RequestHeader("Idempotency-Key") String key, HttpServletRequest request) {
        return meetings.enqueue(workspaceId, id, AuthInterceptor.userId(request), "MINUTE_ACTIONS", key);
    }
}
