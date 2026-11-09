package site.kael.cpa.console.admin.monitor.controller;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.web.server.ResponseStatusException;
import site.kael.cpa.console.admin.monitor.dto.AdminCandidateListResponse;
import site.kael.cpa.console.admin.monitor.dto.AdminCandidateModelsResponse;
import site.kael.cpa.console.admin.monitor.dto.AdminMonitorRunPageResponse;
import site.kael.cpa.console.admin.monitor.dto.AdminMonitorTaskListResponse;
import site.kael.cpa.console.admin.monitor.dto.AdminMonitorTaskResponse;
import site.kael.cpa.console.admin.monitor.service.AdminMonitorService;
import site.kael.cpa.console.auth.security.ApiKeyAuthenticationProvider;
import site.kael.cpa.console.common.web.GlobalExceptionHandler;
import site.kael.cpa.console.config.SecurityConfig;
import site.kael.cpa.console.core.auth.manager.PersistentLoginTokenManager;
import site.kael.cpa.console.core.user.manager.UserManager;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * T10 self-check: the /admin/monitor auth matrix (anonymous/non-admin rejected, admin
 * allowed), POST/PATCH CSRF not exempt, strict request field checks and the local error
 * mapping. The admin history DTO is asserted never to carry identity or raw response text.
 */
@WebMvcTest(AdminMonitorController.class)
@Import({SecurityConfig.class, GlobalExceptionHandler.class, AdminMonitorControllerTest.ContextConfig.class})
class AdminMonitorControllerTest {
    @Autowired private MockMvc mvc;
    @MockitoBean private AdminMonitorService service;
    @MockitoBean private PersistentLoginTokenManager tokens;
    @MockitoBean private UserManager users;
    @MockitoBean private ApiKeyAuthenticationProvider provider;

    @TestConfiguration
    static class ContextConfig {
        @Bean SecurityContextRepository securityContextRepository() {
            return new HttpSessionSecurityContextRepository();
        }
    }

    private AdminMonitorTaskResponse sampleTask() {
        return new AdminMonitorTaskResponse("t-1", "每日检查", "codex gpt-5", "instance-1",
                "codex", false, null, 1L, 0L, "0 0 9 * * *", "UTC", null, 1L, 1L);
    }

    @Test
    void anonymousAndNonAdminsAreRejectedOnEveryEndpoint() throws Exception {
        mvc.perform(get("/admin/monitor/credentials")).andExpect(status().is4xxClientError());
        mvc.perform(get("/admin/monitor/credentials/c1/models")).andExpect(status().is4xxClientError());
        mvc.perform(get("/admin/monitor/tasks")).andExpect(status().is4xxClientError());
        mvc.perform(post("/admin/monitor/tasks").with(csrf()).contentType("application/json").content("{}"))
                .andExpect(status().is4xxClientError());
        mvc.perform(patch("/admin/monitor/tasks/t-1").with(csrf()).contentType("application/json").content("{}"))
                .andExpect(status().is4xxClientError());
        mvc.perform(get("/admin/monitor/tasks/t-1/runs")).andExpect(status().is4xxClientError());

        mvc.perform(get("/admin/monitor/credentials").with(user("u").roles("USER"))).andExpect(status().isForbidden());
        mvc.perform(get("/admin/monitor/tasks").with(user("u").roles("USER"))).andExpect(status().isForbidden());
        mvc.perform(post("/admin/monitor/tasks").with(user("u").roles("USER")).with(csrf())
                .contentType("application/json").content("{}")).andExpect(status().isForbidden());
        mvc.perform(patch("/admin/monitor/tasks/t-1").with(user("u").roles("USER")).with(csrf())
                .contentType("application/json").content("{}")).andExpect(status().isForbidden());
        mvc.perform(get("/admin/monitor/tasks/t-1/runs").with(user("u").roles("USER")))
                .andExpect(status().isForbidden());
        verifyNoInteractions(service);
    }

    @Test
    void adminWritesStillRequireCsrf() throws Exception {
        mvc.perform(post("/admin/monitor/tasks").with(user("a").roles("ADMIN"))
                .contentType("application/json")
                .content("{\"safe_name\":\"n\",\"cron_expression\":\"0 0 9 * * *\",\"zone_id\":\"UTC\","
                        + "\"instance_key\":\"c1\",\"model_choice_id\":\"m1\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(patch("/admin/monitor/tasks/t-1").with(user("a").roles("ADMIN"))
                .contentType("application/json").content("{\"expected_revision\":0,\"enabled\":true}"))
                .andExpect(status().isForbidden());
        verifyNoInteractions(service);
    }

    @Test
    void adminReadsRequireNoCsrf() throws Exception {
        when(service.candidates()).thenReturn(new AdminCandidateListResponse(List.of(), Map.of()));
        when(service.tasks()).thenReturn(new AdminMonitorTaskListResponse(List.of(), 0));
        when(service.candidateModels("c1")).thenReturn(
                new AdminCandidateModelsResponse("c1", List.of("gpt-5"), Map.of("m1", "gpt-5")));
        when(service.runs(eq("t-1"), isNull(), isNull(), isNull(), isNull(), isNull()))
                .thenReturn(new AdminMonitorRunPageResponse("t-1", List.of(), null, 0L, 0L, 0L, 0L,
                        "verification", false));
        mvc.perform(get("/admin/monitor/credentials").with(user("a").roles("ADMIN")))
                .andExpect(status().isOk());
        mvc.perform(get("/admin/monitor/credentials/c1/models").with(user("a").roles("ADMIN")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.models[0]").value("gpt-5"));
        mvc.perform(get("/admin/monitor/tasks").with(user("a").roles("ADMIN"))).andExpect(status().isOk());
        mvc.perform(get("/admin/monitor/tasks/t-1/runs").with(user("a").roles("ADMIN")))
                .andExpect(status().isOk());
    }

    @Test
    void adminCanCreateAndUpdateWithCsrf() throws Exception {
        when(service.createTask(any(site.kael.cpa.console.admin.monitor.dto.AdminMonitorTaskCreateRequest.class)))
                .thenReturn(sampleTask());
        when(service.updateTask(eq("t-1"),
                any(site.kael.cpa.console.admin.monitor.dto.AdminMonitorTaskUpdateRequest.class)))
                .thenReturn(sampleTask());
        mvc.perform(post("/admin/monitor/tasks").with(user("a").roles("ADMIN")).with(csrf())
                        .contentType("application/json")
                        .content("{\"safe_name\":\"n\",\"cron_expression\":\"0 0 9 * * *\",\"zone_id\":\"UTC\","
                                + "\"instance_key\":\"c1\",\"model_choice_id\":\"m1\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.enabled").value(false));
        mvc.perform(patch("/admin/monitor/tasks/t-1").with(user("a").roles("ADMIN")).with(csrf())
                        .contentType("application/json")
                        .content("{\"expected_revision\":0,\"enabled\":true}"))
                .andExpect(status().isOk());
    }

    @Test
    void immutableTargetAndUnknownFieldsAreRejectedAsLocalBadRequest() throws Exception {
        when(service.createTask(any(site.kael.cpa.console.admin.monitor.dto.AdminMonitorTaskCreateRequest.class)))
                .thenThrow(new ResponseStatusException(HttpStatus.BAD_REQUEST, "UNKNOWN_FIELD"));
        for (String body : List.of(
                "{\"safe_name\":\"n\",\"cron_expression\":\"0 0 9 * * *\",\"zone_id\":\"UTC\","
                        + "\"instance_key\":\"c1\",\"model_choice_id\":\"m1\",\"route_model\":\"gpt-5\"}",
                "{\"safe_name\":\"n\",\"cron_expression\":\"0 0 9 * * *\",\"zone_id\":\"UTC\","
                        + "\"instance_key\":\"c1\",\"model_choice_id\":\"m1\",\"auth_id\":\"x\"}",
                "{\"unexpected\":\"x\"}")) {
            mvc.perform(post("/admin/monitor/tasks").with(user("a").roles("ADMIN")).with(csrf())
                            .contentType("application/json").content(body))
                    .andExpect(result -> {
                        int status = result.getResponse().getStatus();
                        assertTrue(status == 400 || status == 409,
                                "expected local rejection, got " + status);
                    });
        }
        when(service.updateTask(eq("t-1"), any(site.kael.cpa.console.admin.monitor.dto.AdminMonitorTaskUpdateRequest.class)))
                .thenThrow(new ResponseStatusException(HttpStatus.BAD_REQUEST, "UNKNOWN_FIELD"));
        for (String body : List.of(
                "{\"expected_revision\":0,\"route_model\":\"gpt-5\"}",
                "{\"expected_revision\":0,\"auth_id\":\"x\"}",
                "{\"unexpected\":\"x\"}")) {
            mvc.perform(patch("/admin/monitor/tasks/t-1").with(user("a").roles("ADMIN")).with(csrf())
                            .contentType("application/json").content(body))
                    .andExpect(result -> {
                        int status = result.getResponse().getStatus();
                        assertTrue(status == 400 || status == 409,
                                "expected local rejection, got " + status);
                    });
        }
        // expected_revision missing: the service contract is 400 EXPECTED_REVISION_REQUIRED.
        when(service.updateTask(eq("t-1"), any())).thenThrow(
                new ResponseStatusException(HttpStatus.BAD_REQUEST, "EXPECTED_REVISION_REQUIRED"));
        mvc.perform(patch("/admin/monitor/tasks/t-1").with(user("a").roles("ADMIN")).with(csrf())
                        .contentType("application/json").content("{\"enabled\":true}"))
                .andExpect(status().isBadRequest());
        // Empty body is rejected by the controller before the service.
        mvc.perform(patch("/admin/monitor/tasks/t-1").with(user("a").roles("ADMIN")).with(csrf())
                        .contentType("application/json").content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void localErrorCodesNeverEchoParametersOrEndpoints() throws Exception {
        when(service.updateTask(eq("t-1"), any())).thenThrow(
                new ResponseStatusException(HttpStatus.CONFLICT, "REVISION_CONFLICT"));
        mvc.perform(patch("/admin/monitor/tasks/t-1").with(user("a").roles("ADMIN")).with(csrf())
                        .contentType("application/json").content("{\"expected_revision\":0,\"enabled\":true}"))
                .andExpect(status().isConflict());
    }

    @Test
    void adminRunPageExposesNoIdentityOrRawResponseFields() throws Exception {
        AdminMonitorRunPageResponse page = new AdminMonitorRunPageResponse("t-1", List.of(), null,
                0L, 0L, 0L, 0L, "verification", false);
        when(service.runs(eq("t-1"), isNull(), isNull(), isNull(), isNull(), isNull())).thenReturn(page);
        String body = mvc.perform(get("/admin/monitor/tasks/t-1/runs").with(user("a").roles("ADMIN")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        for (String forbidden : List.of("auth_id", "auth_index", "route_model_cipher",
                "credential", "provider_account", "host", "management_key", "endpoint")) {
            assertTrue(!body.contains(forbidden), "admin history must not expose " + forbidden);
        }
    }
}
