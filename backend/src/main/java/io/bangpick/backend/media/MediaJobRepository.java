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
                   lease_id, lease_expires, result_par_id, result_key, result_bytes
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

    void insertUpload(UUID id, String ownerId, String videoType, long videoBytes,
                      String inputGrantId, Instant uploadExpiresAt) {
        jdbc.update("""
                INSERT INTO media_jobs (id, owner_id, status, video_type, video_bytes, input_par_id, upload_expires)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """, id.toString(), ownerId, Status.UPLOADING.name(), videoType, videoBytes,
                inputGrantId, uploadExpiresAt.getEpochSecond());
    }

    void markInputQueued(UUID id) {
        jdbc.update("UPDATE media_jobs SET status = ?, input_par_id = NULL WHERE id = ?",
                Status.QUEUED.name(), id.toString());
    }

    void assignLease(UUID id, UUID leaseId, Instant leaseExpiresAt) {
        jdbc.update("""
                UPDATE media_jobs SET status = ?, lease_id = ?, lease_expires = ?,
                    result_par_id = NULL, result_key = NULL, result_bytes = NULL WHERE id = ?
                """, Status.PROCESSING.name(), leaseId.toString(), leaseExpiresAt.getEpochSecond(), id.toString());
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
        return new MediaJob(UUID.fromString(rs.getString("id")), rs.getString("owner_id"),
                Status.valueOf(rs.getString("status")), rs.getString("video_type"), rs.getLong("video_bytes"),
                rs.getString("input_par_id"), Instant.ofEpochSecond(rs.getLong("upload_expires")),
                leaseId == null ? null : UUID.fromString(leaseId),
                leaseExpires == null ? null : Instant.ofEpochSecond(leaseExpires),
                rs.getString("result_par_id"), rs.getString("result_key"), rs.getLong("result_bytes"));
    }
}
