package io.bangpick.backend.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.web.context.HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.oracle.bmc.objectstorage.ObjectStorageClient;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.util.UriComponentsBuilder;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(properties = {
        "bangpick.auth.enabled=true",
        "bangpick.auth.web-origin=http://localhost:8088",
        "bangpick.auth.google-client-id=test-client",
        "bangpick.auth.google-client-secret=test-secret",
        "bangpick.media.enabled=true",
        "bangpick.media.dev-token=" + AccountSecurityTests.DEV_TOKEN,
        "bangpick.media.worker-token=" + AccountSecurityTests.WORKER_TOKEN,
        "spring.datasource.url=jdbc:h2:mem:accounts;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa", "spring.datasource.password="
})
@AutoConfigureMockMvc
class AccountSecurityTests {
    static final String DEV_TOKEN = "example-development-token-for-tests-123";
    static final String WORKER_TOKEN = "example-worker-token-for-tests-456";

    @Autowired MockMvc mvc;
    @Autowired MemberRepository members;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @MockitoBean ObjectStorageClient ociClient;

    @Test
    void loginUsesTheConfiguredOriginEvenWhenThePageWasOpenedAtAnotherHost() throws Exception {
        var options = mvc.perform(get("http://127.0.0.1:8088/api/auth/options").contextPath("/api"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.loginPath").value("http://localhost:8088/api/oauth2/authorization/google"))
                .andReturn().getResponse();
        String loginUrl = json.readTree(options.getContentAsString()).path("loginPath").asString();

        var login = mvc.perform(get(loginUrl).contextPath("/api"))
                .andExpect(status().is3xxRedirection()).andReturn();
        String location = login.getResponse().getRedirectedUrl();
        assertThat(location).startsWith("https://accounts.google.com/");
        var params = UriComponentsBuilder.fromUriString(location).build().getQueryParams();
        assertThat(params.getFirst("redirect_uri"))
                .isEqualTo("http://localhost:8088/api/login/oauth2/code/google");
        assertThat(params.getFirst("state")).isNotBlank();

        mvc.perform(get("/api/login/oauth2/code/google").contextPath("/api")
                        .session((MockHttpSession) login.getRequest().getSession(false))
                        .param("state", params.getFirst("state")).param("error", "access_denied"))
                .andExpect(redirectedUrl("http://localhost:8088/?login=failed"));
    }

    @Test
    void roleChangesApplyToTheSameSessionAndIdentityKeepsItsMemberId() throws Exception {
        var member = members.findOrCreate("google", "test-role-subject");
        var session = memberSession(member.id());
        mvc.perform(get("/api/me").contextPath("/api")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/admin/access").contextPath("/api")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/me").contextPath("/api").session(session))
                .andExpect(jsonPath("$.id").value(member.id().toString()))
                .andExpect(jsonPath("$.role").value("USER"))
                .andExpect(jsonPath("$.displayName").value("Test Member"))
                .andExpect(jsonPath("$.subject").doesNotExist());
        mvc.perform(get("/api/admin/access").contextPath("/api").session(session))
                .andExpect(status().isForbidden());

        jdbc.update("UPDATE members SET role = 'ADMIN' WHERE id = ?", member.id().toString());
        assertThat(members.findOrCreate("google", "test-role-subject"))
                .isEqualTo(new MemberRepository.Member(member.id(), MemberRepository.Role.ADMIN));
        mvc.perform(get("/api/admin/access").contextPath("/api").session(session))
                .andExpect(status().isNoContent());

        jdbc.update("UPDATE members SET role = 'USER' WHERE id = ?", member.id().toString());
        mvc.perform(get("/api/admin/access").contextPath("/api").session(session))
                .andExpect(status().isForbidden());
    }

    @Test
    void logoutRequiresCsrfAndInvalidatesTheSession() throws Exception {
        var member = members.findOrCreate("google", "test-logout-subject");
        var session = memberSession(member.id());
        mvc.perform(post("/api/auth/logout").contextPath("/api").session(session))
                .andExpect(status().isForbidden());
        var response = mvc.perform(get("/api/auth/csrf").contextPath("/api").session(session))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andReturn().getResponse();
        var csrf = json.readTree(response.getContentAsString());
        mvc.perform(post("/api/auth/logout").contextPath("/api").session(session)
                        .header(csrf.get("headerName").asString(), csrf.get("token").asString()))
                .andExpect(status().isNoContent());
        assertThat(session.isInvalid()).isTrue();
    }

    @Test
    void browserMediaUsesTheSessionOwnerAndWorkerCredentialsStaySeparate() throws Exception {
        var alice = members.findOrCreate("google", "test-media-A");
        var bob = members.findOrCreate("google", "test-media-a");
        assertThat(bob.id()).isNotEqualTo(alice.id());
        var id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO media_jobs (id, owner_id, status, video_type, video_bytes, upload_expires)
                VALUES (?, ?, 'UPLOADING', 'video/mp4', 20, 0)
                """, id.toString(), alice.id().toString());
        String job = "/api/media/jobs/" + id;

        mvc.perform(get(job).contextPath("/api").header("Authorization", "Bearer " + DEV_TOKEN))
                .andExpect(status().isUnauthorized());
        mvc.perform(get(job).contextPath("/api").session(memberSession(alice.id())))
                .andExpect(jsonPath("$.jobId").value(id.toString()));
        mvc.perform(get(job).contextPath("/api").session(memberSession(bob.id())))
                .andExpect(status().isNotFound());
        mvc.perform(post(job + "/preview").contextPath("/api").session(memberSession(bob.id())).with(csrf()))
                .andExpect(status().isNotFound());

        String claim = "/api/internal/media/jobs/" + id + "/claim";
        mvc.perform(post(claim).contextPath("/api").session(memberSession(alice.id()))
                        .contentType("application/json").content("{}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(post(claim).contextPath("/api").header("Authorization", "Bearer " + WORKER_TOKEN)
                        .contentType("application/json").content("{}"))
                .andExpect(status().isConflict());
    }

    private static MockHttpSession memberSession(UUID memberId) {
        var token = OidcIdToken.withTokenValue("test-id-token").subject("test-session-subject")
                .claim("name", "Test Member").build();
        var user = new DefaultOidcUser(List.of(new SimpleGrantedAuthority("OIDC_USER")), token);
        var principal = new MemberPrincipal(memberId, user);
        var authentication = new OAuth2AuthenticationToken(principal, principal.getAuthorities(), "google");
        var session = new MockHttpSession();
        session.setAttribute(SPRING_SECURITY_CONTEXT_KEY, new SecurityContextImpl(authentication));
        return session;
    }
}
