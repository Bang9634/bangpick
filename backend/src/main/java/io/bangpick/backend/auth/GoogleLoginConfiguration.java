package io.bangpick.backend.auth;

import java.net.URI;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.web.servlet.ServletContextInitializer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.oauth2.client.CommonOAuth2Provider;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.util.StringUtils;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "bangpick.auth.enabled", havingValue = "true")
class GoogleLoginConfiguration {
    static final String LOGIN_PATH = "/oauth2/authorization/google";
    static final String CALLBACK_PATH = "/login/oauth2/code/google";

    @ConfigurationProperties("bangpick.auth")
    record Settings(boolean enabled, String googleClientId, String googleClientSecret, String webOrigin) {
        URI origin() {
            URI origin;
            try {
                origin = URI.create(webOrigin);
            } catch (IllegalArgumentException ex) {
                throw invalidOrigin();
            }
            String host = origin.getHost();
            boolean loopback = "localhost".equalsIgnoreCase(host) || "127.0.0.1".equals(host)
                    || "[::1]".equals(host);
            boolean secure = "https".equals(origin.getScheme());
            boolean localHttp = loopback && "http".equals(origin.getScheme());
            if (host == null || (!secure && !localHttp) || origin.getUserInfo() != null
                    || origin.getQuery() != null || origin.getFragment() != null
                    || origin.getPort() > 65535 || (!origin.getPath().isEmpty() && !"/".equals(origin.getPath()))) {
                throw invalidOrigin();
            }
            return origin.resolve("/");
        }

        private static IllegalStateException invalidOrigin() {
            return new IllegalStateException("Set BANGPICK_WEB_ORIGIN to an HTTPS origin or a loopback HTTP origin");
        }

        @Override public String toString() { return "GoogleLoginSettings[redacted]"; }
    }

    @Bean
    ClientRegistrationRepository googleClient(Settings settings) {
        if (!StringUtils.hasText(settings.googleClientId()) || !StringUtils.hasText(settings.googleClientSecret())) {
            throw new IllegalStateException("Set GOOGLE_CLIENT_ID and GOOGLE_CLIENT_SECRET to enable Google login");
        }
        var registration = CommonOAuth2Provider.GOOGLE.getBuilder("google")
                .clientId(settings.googleClientId())
                .clientSecret(settings.googleClientSecret())
                .scope("openid", "profile")
                .redirectUri(settings.origin().resolve("/api" + CALLBACK_PATH).toString())
                .build();
        return new InMemoryClientRegistrationRepository(registration);
    }

    @Bean
    ServletContextInitializer googleSessionCookie(Settings settings) {
        // TLS can terminate at the frontend proxy, so derive Secure from the public origin.
        return context -> context.getSessionCookieConfig().setSecure("https".equals(settings.origin().getScheme()));
    }
}
