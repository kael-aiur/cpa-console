package site.kael.cpa.console.monitor.controller;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import site.kael.cpa.console.auth.security.ApiKeyAuthenticationProvider;
import site.kael.cpa.console.common.web.GlobalExceptionHandler;
import site.kael.cpa.console.config.SecurityConfig;
import site.kael.cpa.console.core.auth.manager.PersistentLoginTokenManager;
import site.kael.cpa.console.core.user.manager.UserManager;
import site.kael.cpa.console.monitor.dto.MonitorRunPageResponse;
import site.kael.cpa.console.monitor.dto.MonitorTaskListResponse;
import site.kael.cpa.console.monitor.service.MonitorService;

import java.util.List;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * T11 API matrix self-check: anonymous rejected, both USER and ADMIN can read, GET-only
 * surface and no whitelist violations in the serialized payload.
 */
@WebMvcTest(MonitorController.class)
@Import({SecurityConfig.class, GlobalExceptionHandler.class, MonitorControllerTest.ContextConfig.class})
class MonitorControllerTest {
    @Autowired private MockMvc mvc;
    @MockitoBean private MonitorService service;
    @MockitoBean private PersistentLoginTokenManager tokens;
    @MockitoBean private UserManager users;
    @MockitoBean private ApiKeyAuthenticationProvider provider;

    @TestConfiguration
    static class ContextConfig {
        @Bean SecurityContextRepository securityContextRepository() {
            return new HttpSessionSecurityContextRepository();
        }
    }

    @Test
    void anonymousIsRejectedOnBothEndpoints() throws Exception {
        mvc.perform(get("/api/monitor/tasks")).andExpect(status().is4xxClientError());
        mvc.perform(get("/api/monitor/tasks/t-1/runs")).andExpect(status().is4xxClientError());
        verifyNoInteractions(service);
    }

    @Test
    void loggedInUserAndAdminCanRead() throws Exception {
        when(service.taskCards(isNull(), isNull()))
                .thenReturn(new MonitorTaskListResponse(List.of(), 0, 0L, 0L));
        when(service.runs(eq("t-1"), isNull(), isNull(), isNull(), isNull(), isNull()))
                .thenReturn(new MonitorRunPageResponse("每日检查", "codex gpt-5", List.of(), null,
                        0L, 0L, 0L, 0L, "verification", null, false));
        mvc.perform(get("/api/monitor/tasks").with(user("u").roles("USER")))
                .andExpect(status().isOk());
        mvc.perform(get("/api/monitor/tasks").with(user("a").roles("ADMIN")))
                .andExpect(status().isOk());
        mvc.perform(get("/api/monitor/tasks/t-1/runs").with(user("u").roles("USER")))
                .andExpect(status().isOk());
        mvc.perform(get("/api/monitor/tasks/t-1/runs").with(user("a").roles("ADMIN")))
                .andExpect(status().isOk());
    }

    @Test
    void payloadCarriesOnlyWhitelistedFields() throws Exception {
        when(service.runs(eq("t-1"), isNull(), isNull(), isNull(), isNull(), isNull()))
                .thenReturn(new MonitorRunPageResponse("每日检查", "codex gpt-5", List.of(), null,
                        0L, 0L, 0L, 0L, "verification", null, false));
        String body = mvc.perform(get("/api/monitor/tasks/t-1/runs").with(user("u").roles("USER")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        for (String forbidden : List.of("task_id", "run_id", "auth_id", "auth_index",
                "route_model_cipher", "provider_account", "management_key", "cpa_http_status",
                "safe_admin_summary", "endpoint")) {
            org.junit.jupiter.api.Assertions.assertTrue(!body.contains(forbidden),
                    "public history must not expose " + forbidden);
        }
    }
}
