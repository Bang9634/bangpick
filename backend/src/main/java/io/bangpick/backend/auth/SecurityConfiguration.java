package io.bangpick.backend.auth;

import static org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher.withDefaults;

import jakarta.servlet.DispatcherType;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserService;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.logout.HttpStatusReturningLogoutSuccessHandler;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter.ReferrerPolicy;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(GoogleLoginConfiguration.Settings.class)
class SecurityConfiguration {
    private static final PathPatternRequestMatcher.Builder PATHS = withDefaults();
    private static final PathPatternRequestMatcher[] BROWSER_MEDIA = {
        PATHS.matcher(HttpMethod.POST, "/media/jobs"),
        PATHS.matcher(HttpMethod.GET, "/media/jobs/*"),
        PATHS.matcher(HttpMethod.POST, "/media/jobs/*/input-complete"),
        PATHS.matcher(HttpMethod.POST, "/media/jobs/*/preview")
    };
    private static final PathPatternRequestMatcher[] WORKER_MEDIA = {
        PATHS.matcher(HttpMethod.POST, "/internal/media/jobs/*/claim"),
        PATHS.matcher(HttpMethod.POST, "/internal/media/jobs/*/result-upload"),
        PATHS.matcher(HttpMethod.POST, "/internal/media/jobs/*/result-complete")
    };

    @Bean
    SecurityFilterChain webSecurity(HttpSecurity http, GoogleLoginConfiguration.Settings settings,
                                    MemberRepository members) throws Exception {
        http.authorizeHttpRequests(routes -> {
            routes.dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                    .requestMatchers(HttpMethod.GET, "/auth/options", "/actuator/health",
                            "/actuator/health/liveness", "/actuator/health/readiness").permitAll()
                    // These three routes authenticate the Worker bearer token in MediaController.
                    .requestMatchers(WORKER_MEDIA).permitAll();
            if (settings.enabled()) {
                routes.requestMatchers(HttpMethod.GET, GoogleLoginConfiguration.LOGIN_PATH,
                                GoogleLoginConfiguration.CALLBACK_PATH).permitAll()
                        .requestMatchers("/admin/**").access((authentication, request) ->
                                new AuthorizationDecision(authentication.get().getPrincipal() instanceof MemberPrincipal member
                                        && members.findById(member.memberId())
                                                .filter(existing -> existing.role() == MemberRepository.Role.ADMIN).isPresent()))
                        .requestMatchers("/me", "/auth/csrf").authenticated()
                        .requestMatchers(BROWSER_MEDIA).authenticated();
            } else {
                // The existing bearer-token flow remains available for local OCI transfer development.
                routes.requestMatchers(BROWSER_MEDIA).permitAll();
            }
            routes.anyRequest().denyAll();
        });
        http.csrf(csrf -> {
            csrf.ignoringRequestMatchers(WORKER_MEDIA);
            if (!settings.enabled()) {
                csrf.ignoringRequestMatchers(BROWSER_MEDIA);
            }
        });
        http.exceptionHandling(errors -> errors.authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
                .headers(headers -> headers.referrerPolicy(referrer -> referrer.policy(ReferrerPolicy.NO_REFERRER)))
                .requestCache(cache -> cache.disable())
                .logout(logout -> logout.logoutUrl("/auth/logout").deleteCookies("JSESSIONID")
                        .logoutSuccessHandler(new HttpStatusReturningLogoutSuccessHandler(HttpStatus.NO_CONTENT)));

        if (settings.enabled()) {
            var oidc = new OidcUserService();
            String home = settings.origin().toString();
            http.oauth2Login(login -> login.defaultSuccessUrl(home, true)
                    .failureUrl(home + "?login=failed")
                    .userInfoEndpoint(info -> info.oidcUserService(request -> {
                        var user = oidc.loadUser(request);
                        var member = members.findOrCreate(request.getClientRegistration().getRegistrationId(), user.getSubject());
                        return new MemberPrincipal(member.id(), user);
                    })));
        }
        return http.build();
    }
}
