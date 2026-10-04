package dev.ricardo.notification.application;

import dev.ricardo.notification.api.CreateScheduleRequest;
import dev.ricardo.notification.api.PageResponse;
import dev.ricardo.notification.api.ScheduleResponse;
import dev.ricardo.notification.api.UpdateScheduleRequest;
import dev.ricardo.notification.domain.InvalidScheduleException;
import dev.ricardo.notification.domain.NotificationChannel;
import dev.ricardo.notification.domain.ScheduleConflictException;
import dev.ricardo.notification.domain.ScheduleNotFoundException;
import dev.ricardo.notification.domain.TemplateNotFoundException;
import dev.ricardo.notification.infrastructure.persistence.NotificationScheduleEntity;
import dev.ricardo.notification.infrastructure.persistence.NotificationScheduleRepository;
import dev.ricardo.notification.infrastructure.persistence.TemplateEntity;
import dev.ricardo.notification.infrastructure.persistence.TemplateRepository;
import dev.ricardo.notification.scheduler.ScheduleTimeCalculator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

@Slf4j
@Service
@RequiredArgsConstructor
public class ScheduleService {

    private static final Pattern EMAIL_PATTERN = Pattern.compile("^[A-Za-z0-9+_.-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$");

    private final NotificationScheduleRepository scheduleRepository;
    private final TemplateRepository templateRepository;
    private final SenderFactory senderFactory;
    private final ScheduleTimeCalculator scheduleTimeCalculator;

    @Transactional
    public ScheduleResponse createSchedule(CreateScheduleRequest request, UUID applicationId) {
        if (applicationId == null) {
            throw new InvalidScheduleException("Authenticated application is required to manage schedules");
        }

        scheduleRepository.findByApplicationIdAndName(applicationId, request.name().trim())
                .ifPresent(existing -> {
                    throw new ScheduleConflictException(String.format("A schedule with name '%s' already exists for this application", request.name()));
                });

        validateRecipient(request.channel(), request.recipient());
        senderFactory.get(request.channel());

        templateRepository.findByCodeAndChannel(request.template(), request.channel())
                .orElseThrow(() -> new TemplateNotFoundException(
                        String.format("Template '%s' not found for channel '%s'", request.template(), request.channel())
                ));

        scheduleTimeCalculator.parseAndValidateCron(request.cron());
        scheduleTimeCalculator.parseAndValidateTimezone(request.timezone());

        Instant startInstant = parseOffsetDateTime(request.startAt(), "startAt");
        Instant endInstant = parseOffsetDateTime(request.endAt(), "endAt");

        if (startInstant != null && endInstant != null && !endInstant.isAfter(startInstant)) {
            throw new InvalidScheduleException("endAt must be strictly after startAt");
        }

        Instant now = Instant.now();
        Instant nextRunAt = scheduleTimeCalculator.calculateInitialNextRun(
                request.cron(),
                request.timezone(),
                startInstant,
                endInstant,
                now
        );

        boolean active = true;
        if (endInstant != null && (nextRunAt == null || nextRunAt.isAfter(endInstant))) {
            active = false;
            nextRunAt = null;
        }

        NotificationScheduleEntity entity = NotificationScheduleEntity.builder()
                .applicationId(applicationId)
                .name(request.name().trim())
                .cron(request.cron().trim())
                .timezone(request.timezone().trim())
                .channel(request.channel())
                .recipient(request.recipient().trim())
                .templateCode(request.template().trim())
                .payload(request.safeVariables())
                .active(active)
                .startAt(startInstant)
                .endAt(endInstant)
                .nextRunAt(nextRunAt)
                .build();

        NotificationScheduleEntity saved = scheduleRepository.save(entity);
        log.info("Created notification schedule {} ('{}') for application {}", saved.getId(), saved.getName(), applicationId);
        return ScheduleResponse.from(saved);
    }

    @Transactional(readOnly = true)
    public PageResponse<ScheduleResponse> getSchedules(UUID applicationId, Pageable pageable) {
        if (applicationId == null) {
            throw new InvalidScheduleException("Authenticated application is required to manage schedules");
        }
        Page<NotificationScheduleEntity> page = scheduleRepository.findByApplicationId(applicationId, pageable);
        List<ScheduleResponse> content = page.getContent().stream()
                .map(ScheduleResponse::from)
                .toList();
        return new PageResponse<>(content, page.getNumber(), page.getSize(), page.getTotalElements(), page.getTotalPages());
    }

    @Transactional(readOnly = true)
    public ScheduleResponse getScheduleById(UUID id, UUID applicationId) {
        NotificationScheduleEntity entity = getEntity(id, applicationId);
        return ScheduleResponse.from(entity);
    }

    @Transactional
    public ScheduleResponse updateSchedule(UUID id, UpdateScheduleRequest request, UUID applicationId) {
        NotificationScheduleEntity entity = getEntity(id, applicationId);

        if (!entity.getName().equals(request.name().trim())) {
            scheduleRepository.findByApplicationIdAndName(applicationId, request.name().trim())
                    .ifPresent(existing -> {
                        throw new ScheduleConflictException(String.format("A schedule with name '%s' already exists for this application", request.name()));
                    });
            entity.setName(request.name().trim());
        }

        validateRecipient(request.channel(), request.recipient());
        senderFactory.get(request.channel());

        templateRepository.findByCodeAndChannel(request.template(), request.channel())
                .orElseThrow(() -> new TemplateNotFoundException(
                        String.format("Template '%s' not found for channel '%s'", request.template(), request.channel())
                ));

        scheduleTimeCalculator.parseAndValidateCron(request.cron());
        scheduleTimeCalculator.parseAndValidateTimezone(request.timezone());

        Instant startInstant = parseOffsetDateTime(request.startAt(), "startAt");
        Instant endInstant = parseOffsetDateTime(request.endAt(), "endAt");

        if (startInstant != null && endInstant != null && !endInstant.isAfter(startInstant)) {
            throw new InvalidScheduleException("endAt must be strictly after startAt");
        }

        entity.setCron(request.cron().trim());
        entity.setTimezone(request.timezone().trim());
        entity.setChannel(request.channel());
        entity.setRecipient(request.recipient().trim());
        entity.setTemplateCode(request.template().trim());
        entity.setPayload(request.safeVariables());
        entity.setStartAt(startInstant);
        entity.setEndAt(endInstant);

        if (entity.isActive()) {
            Instant now = Instant.now();
            Instant nextRunAt = scheduleTimeCalculator.calculateInitialNextRun(
                    request.cron(),
                    request.timezone(),
                    startInstant,
                    endInstant,
                    now
            );
            if (endInstant != null && (nextRunAt == null || nextRunAt.isAfter(endInstant))) {
                entity.setActive(false);
                entity.setNextRunAt(null);
            } else {
                entity.setNextRunAt(nextRunAt);
            }
        }

        NotificationScheduleEntity saved = scheduleRepository.save(entity);
        log.info("Updated notification schedule {} ('{}')", saved.getId(), saved.getName());
        return ScheduleResponse.from(saved);
    }

    @Transactional
    public void deleteSchedule(UUID id, UUID applicationId) {
        NotificationScheduleEntity entity = getEntity(id, applicationId);
        scheduleRepository.delete(entity);
        log.info("Deleted notification schedule {} for application {}", id, applicationId);
    }

    @Transactional
    public ScheduleResponse pauseSchedule(UUID id, UUID applicationId) {
        NotificationScheduleEntity entity = getEntity(id, applicationId);
        entity.setActive(false);
        NotificationScheduleEntity saved = scheduleRepository.save(entity);
        log.info("Paused notification schedule {} ('{}')", saved.getId(), saved.getName());
        return ScheduleResponse.from(saved);
    }

    @Transactional
    public ScheduleResponse resumeSchedule(UUID id, UUID applicationId) {
        NotificationScheduleEntity entity = getEntity(id, applicationId);

        Instant now = Instant.now();
        Instant nextRunAt = scheduleTimeCalculator.calculateInitialNextRun(
                entity.getCron(),
                entity.getTimezone(),
                entity.getStartAt(),
                entity.getEndAt(),
                now
        );

        if (entity.getEndAt() != null && (nextRunAt == null || nextRunAt.isAfter(entity.getEndAt()))) {
            entity.setActive(false);
            entity.setNextRunAt(null);
            log.info("Resume called on schedule {} ('{}') but endAt has expired. Remained inactive.", entity.getId(), entity.getName());
        } else {
            entity.setActive(true);
            entity.setNextRunAt(nextRunAt);
            log.info("Resumed notification schedule {} ('{}'). Next run at {}", entity.getId(), entity.getName(), nextRunAt);
        }

        NotificationScheduleEntity saved = scheduleRepository.save(entity);
        return ScheduleResponse.from(saved);
    }

    private NotificationScheduleEntity getEntity(UUID id, UUID applicationId) {
        if (applicationId == null) {
            throw new InvalidScheduleException("Authenticated application is required to manage schedules");
        }
        return scheduleRepository.findByIdAndApplicationId(id, applicationId)
                .orElseThrow(() -> new ScheduleNotFoundException(String.format("Schedule with id '%s' not found", id)));
    }

    private void validateRecipient(NotificationChannel channel, String recipient) {
        if (recipient == null || recipient.isBlank()) {
            throw new InvalidScheduleException("Recipient is required");
        }
        String clean = recipient.trim();
        if (channel == NotificationChannel.EMAIL && !EMAIL_PATTERN.matcher(clean).matches()) {
            throw new InvalidScheduleException("Invalid email format: " + clean);
        }
    }

    private Instant parseOffsetDateTime(String isoString, String fieldName) {
        if (isoString == null || isoString.isBlank()) {
            return null;
        }
        try {
            return OffsetDateTime.parse(isoString.trim()).toInstant();
        } catch (DateTimeParseException ex) {
            throw new InvalidScheduleException(String.format("Invalid %s format. Must be ISO-8601 with offset (e.g. 2026-10-05T10:00:00-03:00)", fieldName));
        }
    }
}
