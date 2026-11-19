package io.bangpick.backend.media;

import java.net.URI;
import java.time.Instant;

interface MediaStorage {
    record Grant(String id, URI url) {
        @Override public String toString() { return "Grant[redacted]"; }
    }
    enum Access { READ, WRITE }

    Grant grant(String key, Access access, Instant expiresAt);
    long size(String key);
    /** 권한 ID가 없거나 이미 회수됐으면 성공으로 취급해 완료·복구 요청의 재시도를 허용한다. */
    void revoke(String grantId);
}
