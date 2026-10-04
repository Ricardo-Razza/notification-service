package dev.ricardo.notification.api;

import dev.ricardo.notification.application.ScheduleService;
import dev.ricardo.notification.domain.InvalidScheduleException;
import dev.ricardo.notification.domain.NotificationChannel;
import dev.ricardo.notification.domain.ScheduleConflictException;
import dev.ricardo.notification.domain.ScheduleNotFoundException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.hamcrest.Matchers.is;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ScheduleController.class)
class ScheduleControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private ScheduleService scheduleService;

    private final UUID appId = UUID.randomUUID();
    private final UUID scheduleId = UUID.randomUUID();

    private ScheduleResponse sampleResponse(boolean active) {
        return new ScheduleResponse(
                scheduleId,
                "Daily Report",
                "0 0 9 * * *",
                "America/Sao_Paulo",
                NotificationChannel.EMAIL,
                "user@example.com",
                "WELCOME",
                Map.of("key", "val"),
                active,
                null,
                null,
                Instant.parse("2026-10-05T12:00:00Z"),
                null,
                Instant.now(),
                Instant.now()
        );
    }

    @Test
    @DisplayName("POST /schedules: cria agendamento com sucesso retornando 201 e Location")
    void shouldCreateScheduleSuccessfully() throws Exception {
        CreateScheduleRequest request = new CreateScheduleRequest(
                "Daily Report",
                "0 0 9 * * *",
                "America/Sao_Paulo",
                NotificationChannel.EMAIL,
                "user@example.com",
                "WELCOME",
                Map.of("key", "val"),
                null,
                null
        );

        when(scheduleService.createSchedule(any(CreateScheduleRequest.class), any()))
                .thenReturn(sampleResponse(true));

        mockMvc.perform(post("/api/v1/schedules")
                        .requestAttr(ApiKeyAuthFilter.AUTHENTICATED_APPLICATION_ID, appId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(header().exists("Location"))
                .andExpect(jsonPath("$.id", is(scheduleId.toString())))
                .andExpect(jsonPath("$.name", is("Daily Report")))
                .andExpect(jsonPath("$.active", is(true)))
                .andExpect(jsonPath("$.nextRunAt").isNotEmpty());
    }

    @Test
    @DisplayName("POST /schedules: cron inválido responde 400 Bad Request com ProblemDetail")
    void shouldReturn400WhenCronIsInvalid() throws Exception {
        CreateScheduleRequest request = new CreateScheduleRequest(
                "Invalid Cron",
                "invalid-cron",
                "America/Sao_Paulo",
                NotificationChannel.EMAIL,
                "user@example.com",
                "WELCOME",
                null,
                null,
                null
        );

        when(scheduleService.createSchedule(any(CreateScheduleRequest.class), any()))
                .thenThrow(new InvalidScheduleException("Invalid cron expression: must have 6 fields"));

        mockMvc.perform(post("/api/v1/schedules")
                        .requestAttr(ApiKeyAuthFilter.AUTHENTICATED_APPLICATION_ID, appId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title", is("Invalid Request")))
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("Invalid cron expression")));
    }

    @Test
    @DisplayName("POST /schedules: timezone inválido responde 400 Bad Request com ProblemDetail")
    void shouldReturn400WhenTimezoneIsInvalid() throws Exception {
        CreateScheduleRequest request = new CreateScheduleRequest(
                "Invalid Timezone",
                "0 0 9 * * *",
                "Invalid/TZ",
                NotificationChannel.EMAIL,
                "user@example.com",
                "WELCOME",
                null,
                null,
                null
        );

        when(scheduleService.createSchedule(any(CreateScheduleRequest.class), any()))
                .thenThrow(new InvalidScheduleException("Invalid timezone: Invalid/TZ"));

        mockMvc.perform(post("/api/v1/schedules")
                        .requestAttr(ApiKeyAuthFilter.AUTHENTICATED_APPLICATION_ID, appId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title", is("Invalid Request")))
                .andExpect(jsonPath("$.detail", is("Invalid timezone: Invalid/TZ")));
    }

    @Test
    @DisplayName("POST /schedules: nome duplicado para mesma aplicação responde 409 Conflict")
    void shouldReturn409WhenScheduleNameAlreadyExists() throws Exception {
        CreateScheduleRequest request = new CreateScheduleRequest(
                "Daily Report",
                "0 0 9 * * *",
                "America/Sao_Paulo",
                NotificationChannel.EMAIL,
                "user@example.com",
                "WELCOME",
                null,
                null,
                null
        );

        when(scheduleService.createSchedule(any(CreateScheduleRequest.class), any()))
                .thenThrow(new ScheduleConflictException("A schedule with name 'Daily Report' already exists for this application"));

        mockMvc.perform(post("/api/v1/schedules")
                        .requestAttr(ApiKeyAuthFilter.AUTHENTICATED_APPLICATION_ID, appId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.title", is("Conflict")))
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("already exists")));
    }

    @Test
    @DisplayName("POST /schedules/{id}/pause: pausa agendamento com sucesso")
    void shouldPauseScheduleSuccessfully() throws Exception {
        when(scheduleService.pauseSchedule(eq(scheduleId), any()))
                .thenReturn(sampleResponse(false));

        mockMvc.perform(post("/api/v1/schedules/{id}/pause", scheduleId)
                        .requestAttr(ApiKeyAuthFilter.AUTHENTICATED_APPLICATION_ID, appId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id", is(scheduleId.toString())))
                .andExpect(jsonPath("$.active", is(false)));
    }

    @Test
    @DisplayName("POST /schedules/{id}/resume: retoma agendamento com sucesso recalculando nextRunAt")
    void shouldResumeScheduleSuccessfully() throws Exception {
        when(scheduleService.resumeSchedule(eq(scheduleId), any()))
                .thenReturn(sampleResponse(true));

        mockMvc.perform(post("/api/v1/schedules/{id}/resume", scheduleId)
                        .requestAttr(ApiKeyAuthFilter.AUTHENTICATED_APPLICATION_ID, appId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id", is(scheduleId.toString())))
                .andExpect(jsonPath("$.active", is(true)));
    }

    @Test
    @DisplayName("GET /schedules/{id}: 404 Not Found quando agendamento não existe ou pertence a outra aplicação")
    void shouldReturn404WhenScheduleNotFound() throws Exception {
        when(scheduleService.getScheduleById(eq(scheduleId), any()))
                .thenThrow(new ScheduleNotFoundException("Schedule with id '" + scheduleId + "' not found"));

        mockMvc.perform(get("/api/v1/schedules/{id}", scheduleId)
                        .requestAttr(ApiKeyAuthFilter.AUTHENTICATED_APPLICATION_ID, appId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title", is("Resource Not Found")));
    }

    @Test
    @DisplayName("DELETE /schedules/{id}: remove agendamento retornando 204 No Content")
    void shouldDeleteScheduleSuccessfully() throws Exception {
        mockMvc.perform(delete("/api/v1/schedules/{id}", scheduleId)
                        .requestAttr(ApiKeyAuthFilter.AUTHENTICATED_APPLICATION_ID, appId))
                .andExpect(status().isNoContent());
    }
}
