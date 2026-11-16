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
    // 완료 확인을 재시도할 수 있도록 이미 회수된 권한에 대한 요청도 성공으로 취급한다.
    void revoke(String grantId);
}
