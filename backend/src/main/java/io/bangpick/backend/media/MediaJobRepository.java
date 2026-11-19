package io.bangpick.backend.media;

import io.bangpick.backend.media.MediaJob.Status;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

final class MediaJobRepository {
    private static final String SELECT_BY_ID = """
            SELECT id, owner_id, status, video_type, video_bytes, input_par_id, upload_expires,
                   lease_id, lease_expires, available_at, attempt_count, last_failure_lease_id, last_error_code,
                   result_par_id, result_key, result_bytes
            FROM media_jobs WHERE id = ?
            """;

    private final JdbcTemplate jdbc;

    MediaJobRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    Optional<MediaJob> findById(UUID id) {
        return queryJob(SELECT_BY_ID, id);
    }

    // 조회와 상태 변경이 같은 서비스 트랜잭션에 있어야 행 잠금이 유지된다.
    Optional<MediaJob> findByIdForUpdate(UUID id) {
        return queryJob(SELECT_BY_ID + "FOR UPDATE", id);
    }

    /**
     * 실행 가능한 행을 대기 시각·ID 순으로 잠근다. 이미 잠긴 행은 건너뛰므로
     * 동시 요청 간 절대적인 FIFO 순서를 보장하지 않는다. 호출자는 점유 기록까지
     * 같은 짧은 트랜잭션에서 끝내야 중복 점유를 막을 수 있다.
     */
    Optional<UUID> findNextQueuedForUpdate(Instant now, int maxAttempts) {
        return jdbc.query("""
                SELECT id FROM media_jobs
                WHERE status = ? AND available_at <= ? AND attempt_count < ?
                ORDER BY queued_at, id
                LIMIT 1 FOR UPDATE SKIP LOCKED
                """, (rs, rowNumber) -> UUID.fromString(rs.getString("id")),
                Status.QUEUED.name(), now.getEpochSecond(), maxAttempts).stream().findFirst();
    }

    void insertUpload(UUID id, String ownerId, String videoType, long videoBytes,
                      String inputGrantId, Instant uploadExpiresAt) {
        jdbc.update("""
                INSERT INTO media_jobs (id, owner_id, status, video_type, video_bytes, input_par_id, upload_expires)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """, id.toString(), ownerId, Status.UPLOADING.name(), videoType, videoBytes,
                inputGrantId, uploadExpiresAt.getEpochSecond());
    }

    void markInputQueued(UUID id, Instant queuedAt) {
        long seconds = queuedAt.getEpochSecond();

        jdbc.update("""
                UPDATE media_jobs
                SET status = ?, input_par_id = NULL,
                    queued_at = ?, available_at = ?
                WHERE id = ?
                """, Status.QUEUED.name(), seconds, seconds, id.toString());
    }

    void assignLease(UUID id, UUID leaseId, Instant leaseExpiresAt) {
        jdbc.update("""
                UPDATE media_jobs SET status = ?, lease_id = ?, lease_expires = ?,
                    attempt_count = attempt_count + 1,
                    last_failure_lease_id = NULL,
                    result_par_id = NULL, result_key = NULL, result_bytes = NULL WHERE id = ?
                """, Status.PROCESSING.name(), leaseId.toString(), leaseExpiresAt.getEpochSecond(), id.toString());
    }

    void recordFailure(UUID id, UUID leaseId, Status nextStatus, Instant availableAt, String errorCode) {
        jdbc.update("""
                UPDATE media_jobs
                SET status = ?, available_at = ?, lease_id = NULL, lease_expires = NULL,
                    last_error_code = ?, last_failure_lease_id = ?
                WHERE id = ? AND status = ? AND lease_id = ?
                """, nextStatus.name(), availableAt == null ? null : availableAt.getEpochSecond(),
                errorCode, leaseId.toString(), id.toString(), Status.PROCESSING.name(), leaseId.toString());
    }

    /** 조회 후 점유가 바뀌었거나 만료 조건이 달라진 행에는 복구를 적용하지 않는다. */
    int recordExpiredRecovery(UUID id, UUID leaseId, Instant now, Status nextStatus, Instant availableAt) {
        return jdbc.update("""
                UPDATE media_jobs
                SET status = ?, available_at = ?, lease_id = NULL, lease_expires = NULL,
                    result_par_id = NULL, result_key = NULL, result_bytes = NULL,
                    last_error_code = ?, last_failure_lease_id = ?
                WHERE id = ? AND status = ? AND lease_id = ? AND lease_expires <= ?
                """, nextStatus.name(), availableAt == null ? null : availableAt.getEpochSecond(),
                "LEASE_EXPIRED", leaseId.toString(), id.toString(), Status.PROCESSING.name(),
                leaseId.toString(), now.getEpochSecond());
    }

    int extendLease(UUID id, UUID leaseId, Instant now, Instant leaseExpiresAt) {
        return jdbc.update("""
                UPDATE media_jobs SET lease_expires = ?
                WHERE id = ? AND status = ? AND lease_id = ? AND lease_expires > ?
                """, leaseExpiresAt.getEpochSecond(), id.toString(), Status.PROCESSING.name(),
                leaseId.toString(), now.getEpochSecond());
    }

    void recordResultUpload(UUID id, String resultGrantId, String resultKey, long resultBytes) {
        jdbc.update("UPDATE media_jobs SET result_par_id = ?, result_key = ?, result_bytes = ? WHERE id = ?",
                resultGrantId, resultKey, resultBytes, id.toString());
    }

    void markResultReady(UUID id) {
        jdbc.update("UPDATE media_jobs SET status = ?, result_par_id = NULL WHERE id = ?",
                Status.RESULT_READY.name(), id.toString());
    }

    private Optional<MediaJob> queryJob(String sql, UUID id) {
        return jdbc.query(sql, MediaJobRepository::readJob, id.toString()).stream().findFirst();
    }

    private static MediaJob readJob(ResultSet rs, int rowNumber) throws SQLException {
        String leaseId = rs.getString("lease_id");
        // 만료 시각은 Unix 초 단위로 저장하며, 아직 점유하지 않은 작업의 NULL은 그대로 읽는다.
        Long leaseExpires = rs.getObject("lease_expires", Long.class);
        Long availableAt = rs.getObject("available_at", Long.class);
        String lastFailureLeaseId = rs.getString("last_failure_lease_id");
        return new MediaJob(UUID.fromString(rs.getString("id")), rs.getString("owner_id"),
                Status.valueOf(rs.getString("status")), rs.getString("video_type"), rs.getLong("video_bytes"),
                rs.getString("input_par_id"), Instant.ofEpochSecond(rs.getLong("upload_expires")),
                leaseId == null ? null : UUID.fromString(leaseId),
                leaseExpires == null ? null : Instant.ofEpochSecond(leaseExpires),
                availableAt == null ? null : Instant.ofEpochSecond(availableAt), rs.getInt("attempt_count"),
                lastFailureLeaseId == null ? null : UUID.fromString(lastFailureLeaseId), rs.getString("last_error_code"),
                rs.getString("result_par_id"), rs.getString("result_key"), rs.getLong("result_bytes"));
    }
}
