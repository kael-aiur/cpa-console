package site.kael.cpa.console.admin.credential.controller;

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
import site.kael.cpa.console.admin.credential.dto.AdminCredentialResetResponse;
import site.kael.cpa.console.admin.credential.exception.CredentialResetException;
import site.kael.cpa.console.admin.credential.service.AdminCredentialService;
import site.kael.cpa.console.auth.security.ApiKeyAuthenticationProvider;
import site.kael.cpa.console.common.web.GlobalExceptionHandler;
import site.kael.cpa.console.config.SecurityConfig;
import site.kael.cpa.console.core.auth.manager.PersistentLoginTokenManager;
import site.kael.cpa.console.core.user.manager.UserManager;
import site.kael.cpa.console.quota.dto.QuotaInfoResponse;

import java.util.List;
import java.util.Map;

import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(AdminCredentialController.class)
@Import({SecurityConfig.class, GlobalExceptionHandler.class, AdminCredentialControllerTest.ContextConfig.class})
class AdminCredentialControllerTest {
    @Autowired private MockMvc mvc;
    @MockitoBean private AdminCredentialService service;
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
    void anonymousAndNonAdminsCannotQueryOrReset() throws Exception {
        mvc.perform(get("/admin/credentials/1/quota")).andExpect(status().is4xxClientError());
        mvc.perform(post("/admin/credentials/1/reset-quota").with(csrf())).andExpect(status().is4xxClientError());
        mvc.perform(get("/admin/credentials/1/quota").with(user("user").roles("USER"))).andExpect(status().isForbidden());
        mvc.perform(post("/admin/credentials/1/reset-quota").with(user("user").roles("USER")).with(csrf())).andExpect(status().isForbidden());
        verifyNoInteractions(service);
    }

    @Test
    void adminResetRequiresCsrf() throws Exception {
        mvc.perform(post("/admin/credentials/1/reset-quota").with(user("admin").roles("ADMIN"))).andExpect(status().isForbidden());
        verifyNoInteractions(service);
    }

    @Test
    void adminCanQueryAndResetWithCsrf() throws Exception {
        when(service.getQuota(1L)).thenReturn(new QuotaInfoResponse(Map.of("provider", "codex", "windows", List.of())));
        when(service.resetQuota(1L)).thenReturn(new AdminCredentialResetResponse("ok", "completed", "completed", "重置成功"));
        mvc.perform(get("/admin/credentials/1/quota").with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.quota.provider").value("codex"));
        mvc.perform(post("/admin/credentials/1/reset-quota").with(user("admin").roles("ADMIN")).with(csrf()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.cooldown_reset").value("completed"));
    }

    @Test
    void partialSuccessSurvivesExceptionMapping() throws Exception {
        when(service.resetQuota(1L)).thenThrow(new CredentialResetException(
                new AdminCredentialResetResponse("partial_success", "completed", "failed", "冷却重置失败")));
        mvc.perform(post("/admin/credentials/1/reset-quota").with(user("admin").roles("ADMIN")).with(csrf()))
                .andExpect(status().isBadGateway()).andExpect(jsonPath("$.status").value("partial_success"))
                .andExpect(jsonPath("$.quota_reset").value("completed"));
    }
}
