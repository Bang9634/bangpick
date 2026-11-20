package io.bangpick.backend.media;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.springframework.http.HttpStatus.UNAUTHORIZED;

import io.bangpick.backend.auth.MemberPrincipal;
import java.security.MessageDigest;
import org.springframework.web.server.ResponseStatusException;

/** 개발 토큰은 로컬 소유자, Worker 토큰은 처리, 복구 토큰은 수동 복구에만 사용한다. */
final class MediaAccess {
    private final boolean memberLoginEnabled;
    private final String devToken;
    private final String workerToken;
    private final String recoveryToken;

    MediaAccess(boolean memberLoginEnabled, String devToken, String workerToken, String recoveryToken) {
        if (!isValidToken(workerToken)) {
            throw new IllegalStateException("Configure a worker token (32–128 URL-safe characters)");
        }
        if (!memberLoginEnabled && (!isValidToken(devToken) || devToken.equals(workerToken))) {
            throw new IllegalStateException("Configure a separate development token (32–128 URL-safe characters)");
        }
        if (recoveryToken != null && !recoveryToken.isEmpty()
                && (!isValidToken(recoveryToken) || recoveryToken.equals(devToken)
                || recoveryToken.equals(workerToken))) {
            throw new IllegalStateException("Configure a distinct recovery token (32–128 URL-safe characters)");
        }
        this.memberLoginEnabled = memberLoginEnabled;
        this.devToken = devToken;
        this.workerToken = workerToken;
        this.recoveryToken = recoveryToken == null || recoveryToken.isEmpty() ? null : recoveryToken;
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

    void requireRecovery(String authorization) {
        if (recoveryToken == null) {
            throw new ResponseStatusException(UNAUTHORIZED, "Recovery access is not configured");
        }
        requireToken(recoveryToken, authorization);
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
