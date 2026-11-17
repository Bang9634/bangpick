package io.bangpick.backend.media;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.springframework.http.HttpStatus.UNAUTHORIZED;

import io.bangpick.backend.auth.MemberPrincipal;
import java.security.MessageDigest;
import org.springframework.web.server.ResponseStatusException;

final class MediaAccess {
    private final boolean memberLoginEnabled;
    private final String devToken;
    private final String workerToken;

    MediaAccess(boolean memberLoginEnabled, String devToken, String workerToken) {
        if (!isValidToken(workerToken)) {
            throw new IllegalStateException("Configure a worker token (32–128 URL-safe characters)");
        }
        if (!memberLoginEnabled && (!isValidToken(devToken) || devToken.equals(workerToken))) {
            throw new IllegalStateException("Configure a separate development token (32–128 URL-safe characters)");
        }
        this.memberLoginEnabled = memberLoginEnabled;
        this.devToken = devToken;
        this.workerToken = workerToken;
    }

    String requireOwner(MemberPrincipal principal, String authorization) {
        if (memberLoginEnabled) {
            if (principal == null) throw new ResponseStatusException(UNAUTHORIZED);
            return principal.memberId().toString();
        }
        // The local transfer mode keeps its own owner, separate from account IDs.
        requireToken(devToken, authorization);
        return "local-development";
    }

    void requireWorker(String authorization) {
        requireToken(workerToken, authorization);
    }

    private static boolean isValidToken(String token) {
        return token != null && token.matches("[A-Za-z0-9_-]{32,128}");
    }

    private static void requireToken(String token, String authorization) {
        if (authorization == null || !MessageDigest.isEqual(
                ("Bearer " + token).getBytes(UTF_8), authorization.getBytes(UTF_8))) {
            throw new ResponseStatusException(UNAUTHORIZED, "Access credentials are missing or invalid");
        }
    }
}
