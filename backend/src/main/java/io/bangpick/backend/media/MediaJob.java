package io.bangpick.backend.media;

import java.time.Instant;
import java.util.UUID;

record MediaJob(UUID id, String ownerId, Status status, String videoType, long videoBytes,
                String inputGrantId, Instant uploadExpiresAt, UUID leaseId, Instant leaseExpiresAt,
                Instant availableAt, int attemptCount, UUID lastFailureLeaseId,
                String resultGrantId, String resultKey, long resultBytes) {
    enum Status { UPLOADING, QUEUED, PROCESSING, RESULT_READY, FAILED }

    boolean hasActiveLease(Instant now) {
        return status == Status.PROCESSING && leaseExpiresAt != null && leaseExpiresAt.isAfter(now);
    }

    // 레코드를 로그에 남겨도 점유 ID와 전송 권한 ID는 노출하지 않는다.
    @Override public String toString() { return "MediaJob[id=" + id + ", status=" + status + "]"; }
}
