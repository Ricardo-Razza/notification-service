package dev.ricardo.notification.api;

import dev.ricardo.notification.application.ScheduleService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.net.URI;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/schedules")
@RequiredArgsConstructor
public class ScheduleController {

    private final ScheduleService scheduleService;

    @PostMapping
    public ResponseEntity<ScheduleResponse> createSchedule(
            @RequestAttribute(value = ApiKeyAuthFilter.AUTHENTICATED_APPLICATION_ID, required = false) UUID applicationId,
            @Valid @RequestBody CreateScheduleRequest request
    ) {
        ScheduleResponse created = scheduleService.createSchedule(request, applicationId);
        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}")
                .buildAndExpand(created.id())
                .toUri();
        return ResponseEntity.created(location).body(created);
    }

    @GetMapping
    public PageResponse<ScheduleResponse> getSchedules(
            @RequestAttribute(value = ApiKeyAuthFilter.AUTHENTICATED_APPLICATION_ID, required = false) UUID applicationId,
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "20") int size
    ) {
        Pageable pageable = PageRequest.of(page, size, Sort.by("createdAt").descending());
        return scheduleService.getSchedules(applicationId, pageable);
    }

    @GetMapping("/{id}")
    public ScheduleResponse getScheduleById(
            @RequestAttribute(value = ApiKeyAuthFilter.AUTHENTICATED_APPLICATION_ID, required = false) UUID applicationId,
            @PathVariable("id") UUID id
    ) {
        return scheduleService.getScheduleById(id, applicationId);
    }

    @PutMapping("/{id}")
    public ScheduleResponse updateSchedule(
            @RequestAttribute(value = ApiKeyAuthFilter.AUTHENTICATED_APPLICATION_ID, required = false) UUID applicationId,
            @PathVariable("id") UUID id,
            @Valid @RequestBody UpdateScheduleRequest request
    ) {
        return scheduleService.updateSchedule(id, request, applicationId);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteSchedule(
            @RequestAttribute(value = ApiKeyAuthFilter.AUTHENTICATED_APPLICATION_ID, required = false) UUID applicationId,
            @PathVariable("id") UUID id
    ) {
        scheduleService.deleteSchedule(id, applicationId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/pause")
    public ScheduleResponse pauseSchedule(
            @RequestAttribute(value = ApiKeyAuthFilter.AUTHENTICATED_APPLICATION_ID, required = false) UUID applicationId,
            @PathVariable("id") UUID id
    ) {
        return scheduleService.pauseSchedule(id, applicationId);
    }

    @PostMapping("/{id}/resume")
    public ScheduleResponse resumeSchedule(
            @RequestAttribute(value = ApiKeyAuthFilter.AUTHENTICATED_APPLICATION_ID, required = false) UUID applicationId,
            @PathVariable("id") UUID id
    ) {
        return scheduleService.resumeSchedule(id, applicationId);
    }
}
