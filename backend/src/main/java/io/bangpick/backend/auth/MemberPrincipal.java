package io.bangpick.backend.auth;

import java.io.Serial;
import java.util.UUID;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;

public final class MemberPrincipal extends DefaultOidcUser {
    @Serial
    private static final long serialVersionUID = 1L;

    private final UUID memberId;

    MemberPrincipal(UUID memberId, OidcUser user) {
        super(user.getAuthorities(), user.getIdToken(), user.getUserInfo());
        this.memberId = memberId;
    }

    public UUID memberId() {
        return memberId;
    }
}
