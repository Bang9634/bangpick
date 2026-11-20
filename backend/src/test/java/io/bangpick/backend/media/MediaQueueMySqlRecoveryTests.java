package io.bangpick.backend.media;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.http.HttpStatus.CONFLICT;

import com.oracle.bmc.objectstorage.ObjectStorageClient;
import io.bangpick.backend.BangpickBackendApplication;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.server.ResponseStatusException;

/** Runs only when the configured URL names a local bangpick_queue_test database. */
@EnabledIfEnvironmentVariable(named = "BANGPICK_QUEUE_TEST_MYSQL_URL",
        matches = "jdbc:mysql://(127\\.0\\.0\\.1|localhost):[0-9]+/bangpick_queue_test")
@SpringBootTest(classes = BangpickBackendApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "bangpick.media.enabled=true", "spring.flyway.enabled=true",
        "bangpick.auth.enabled=false",
        "bangpick.media.dev-token=" + MediaTransferTests.OWNER,
        "bangpick.media.worker-token=" + MediaTransferTests.WORKER,
        "bangpick.media.recovery-token=" + MediaTransferTests.RECOVERY,
        "spring.datasource.url=${BANGPICK_QUEUE_TEST_MYSQL_URL}",
        "spring.datasource.username=${BANGPICK_QUEUE_TEST_MYSQL_USER}",
        "spring.datasource.password=${BANGPICK_QUEUE_TEST_MYSQL_PASSWORD}"
})
@Import(MediaTransferTests.StorageConfiguration.class)
class MediaQueueMySqlRecoveryTests {
    @MockitoBean ObjectStorageClient ociClient;
    private static final String RECOVERY_TOKEN = "example-recovery-token-for-tests-789";

    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired MediaJobs jobs;
    @Autowired MediaTransferTests.FakeStorage storage;
    private final HttpClient client = HttpClient.newHttpClient();
    private final List<UUID> created = new ArrayList<>();

    @AfterEach
    void removeTestJobs() {
        for (UUID id : created) {
            jdbc.update("DELETE FROM media_jobs WHERE id = ?", id.toString());
        }
    }

    @Test
    void expiredAttemptWaitsForConfirmationAndCannotReuseItsResult() throws Exception {
        UUID id = queuedJob();
        UUID lease = jobs.claimNext().orElseThrow().leaseId();
        String resultUrl = jobs.startResult(id, lease, 80L).url().toString();
        assertThat(storage.permissions(resultUrl)).isNotNull();

        jdbc.update("UPDATE media_jobs SET lease_expires = 0 WHERE id = ?", id.toString());
        assertThat(jobs.claimNext()).isEmpty();
        assertThat(assertThrows(ResponseStatusException.class, () -> jobs.claim(id, null)).getStatusCode())
                .isEqualTo(CONFLICT);
        assertThat(recover(id, lease, false, true).statusCode()).isEqualTo(400);
        assertThat(storage.permissions(resultUrl)).isNotNull();

        long beforeRecovery = Instant.now().getEpochSecond();
        assertThat(recover(id, lease, true, true).statusCode()).isEqualTo(200);
        assertThat(jdbc.queryForObject("SELECT status FROM media_jobs WHERE id = ?", String.class,
                id.toString())).isEqualTo("QUEUED");
        Long availableAt = jdbc.queryForObject("SELECT available_at FROM media_jobs WHERE id = ?", Long.class,
                id.toString());
        assertThat(availableAt).isBetween(beforeRecovery + 30, beforeRecovery + 32);
        assertThat(storage.permissions(resultUrl)).isNull();
        assertThat(jdbc.queryForObject("SELECT result_key FROM media_jobs WHERE id = ?", String.class,
                id.toString())).isNull();
        assertThat(jdbc.queryForObject("SELECT lease_id FROM media_jobs WHERE id = ?", String.class,
                id.toString())).isNull();
        assertThat(jobs.claimNext()).isEmpty();
        assertThat(assertThrows(ResponseStatusException.class, () -> jobs.finishResult(id, lease)).getStatusCode())
                .isEqualTo(CONFLICT);
        assertThat(recover(id, lease, true, true).statusCode()).isEqualTo(200);
        assertThat(jdbc.queryForObject("SELECT available_at FROM media_jobs WHERE id = ?", Long.class,
                id.toString())).isEqualTo(availableAt);

        jdbc.update("UPDATE media_jobs SET available_at = 0 WHERE id = ?", id.toString());
        assertThat(jobs.claimNext().orElseThrow().leaseId()).isNotEqualTo(lease);
    }

    @Test
    void thirdExpiredAttemptFailsEvenWhenRetryWasRequested() throws Exception {
        UUID id = queuedJob();
        for (int attempt = 1; attempt <= 3; attempt++) {
            UUID lease = jobs.claimNext().orElseThrow().leaseId();
            jdbc.update("UPDATE media_jobs SET lease_expires = 0 WHERE id = ?", id.toString());
            long beforeRecovery = Instant.now().getEpochSecond();
            MediaJob.Status expected = attempt < 3 ? MediaJob.Status.QUEUED : MediaJob.Status.FAILED;
            assertThat(recover(id, lease, true, true).statusCode()).isEqualTo(200);
            assertThat(jdbc.queryForObject("SELECT status FROM media_jobs WHERE id = ?", String.class,
                    id.toString())).isEqualTo(expected.name());
            Long availableAt = jdbc.queryForObject("SELECT available_at FROM media_jobs WHERE id = ?", Long.class,
                    id.toString());
            if (attempt < 3) {
                assertThat(availableAt).isBetween(beforeRecovery + 30L * attempt, beforeRecovery + 30L * attempt + 2);
                jdbc.update("UPDATE media_jobs SET available_at = 0 WHERE id = ?", id.toString());
            } else {
                assertThat(availableAt).isNull();
            }
        }
        assertThat(jdbc.queryForObject("SELECT attempt_count FROM media_jobs WHERE id = ?", Integer.class,
                id.toString())).isEqualTo(3);
        assertThat(jobs.claimNext()).isEmpty();
    }

    private UUID queuedJob() {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO media_jobs (id, owner_id, status, video_type, video_bytes,
                                        upload_expires, queued_at, available_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """, id.toString(), "mysql-recovery-test", "QUEUED", "video/mp4", 1L, 0L, 100L, 0L);
        created.add(id);
        return id;
    }

    private HttpResponse<String> recover(UUID id, UUID lease, boolean previousExecutionStopped,
                                         boolean retryable) throws Exception {
        String request = "{\"leaseId\":\"" + lease + "\",\"previousExecutionStopped\":"
                + previousExecutionStopped + ",\"retryable\":" + retryable + "}";
        return client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port
                        + "/api/internal/media/jobs/" + id + "/recover-expired"))
                .header("Authorization", "Bearer " + RECOVERY_TOKEN)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(request)).build(), HttpResponse.BodyHandlers.ofString());
    }
}
