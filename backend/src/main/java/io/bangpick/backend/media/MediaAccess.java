package io.bangpick.backend.media;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.springframework.http.HttpStatus.UNAUTHORIZED;

import java.security.MessageDigest;
import org.springframework.web.server.ResponseStatusException;

/** 개발 모드에서는 브라우저 토큰 하나가 작업 소유자 하나에 대응한다. 복구 토큰은 수동 복구 전용이다. */
final class MediaAccess {
    private final String devToken;
    private final String workerToken;
    private final String recoveryToken;

    MediaAccess(String devToken, String workerToken, String recoveryToken) {
        if (devToken == null || workerToken == null
                || !devToken.matches("[A-Za-z0-9_-]{32,128}")
                || !workerToken.matches("[A-Za-z0-9_-]{32,128}") || devToken.equals(workerToken)) {
            throw new IllegalStateException("Configure distinct development and worker tokens (32–128 URL-safe characters)");
        }
        if (recoveryToken != null && !recoveryToken.isEmpty()
                && (!recoveryToken.matches("[A-Za-z0-9_-]{32,128}")
                || recoveryToken.equals(devToken) || recoveryToken.equals(workerToken))) {
            throw new IllegalStateException("Configure a distinct recovery token (32–128 URL-safe characters)");
        }
        this.devToken = devToken;
        this.workerToken = workerToken;
        this.recoveryToken = recoveryToken == null || recoveryToken.isEmpty() ? null : recoveryToken;
    }

    String requireOwner(String authorization) {
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

    private static void requireToken(String token, String authorization) {
        if (authorization == null || !MessageDigest.isEqual(
                ("Bearer " + token).getBytes(UTF_8), authorization.getBytes(UTF_8))) {
            throw new ResponseStatusException(UNAUTHORIZED, "Access credentials are missing or invalid");
        }
    }
}
