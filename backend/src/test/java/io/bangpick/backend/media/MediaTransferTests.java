package io.bangpick.backend.media;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.HttpStatus.CONFLICT;
import static org.springframework.http.HttpStatus.UNAUTHORIZED;

import com.oracle.bmc.objectstorage.ObjectStorageClient;
import io.bangpick.backend.BangpickBackendApplication;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(classes = BangpickBackendApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "bangpick.media.enabled=true", "spring.flyway.enabled=true",
        "bangpick.auth.enabled=false",
        "bangpick.media.dev-token=" + MediaTransferTests.OWNER,
        "bangpick.media.worker-token=" + MediaTransferTests.WORKER,
        "bangpick.media.recovery-token=" + MediaTransferTests.RECOVERY,
        "spring.datasource.url=jdbc:h2:mem:mediaflow;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa", "spring.datasource.password="
})
@Import(MediaTransferTests.StorageConfiguration.class)
class MediaTransferTests {
    static final String OWNER = "example-development-token-for-tests-123";
    static final String WORKER = "example-worker-token-for-tests-456";
    static final String RECOVERY = "example-recovery-token-for-tests-789";

    @TestConfiguration(proxyBeanMethods = false)
    static class StorageConfiguration {
        @Bean @Primary FakeStorage storage() { return new FakeStorage(); }
    }

    static class FakeStorage implements MediaStorage {
        record Permissions(String key, Access access) {}
        private final Map<String, Permissions> grants = new ConcurrentHashMap<>();
        private final Map<String, Long> objects = new ConcurrentHashMap<>();

        @Override public Grant grant(String key, Access access, Instant expiry) {
            String id = UUID.randomUUID().toString();
            grants.put(id, new Permissions(key, access));
            return new Grant(id, URI.create("https://storage.example.test/" + id));
        }

        @Override public long size(String key) {
            Long size = objects.get(key);
            if (size == null) throw new ResponseStatusException(CONFLICT);
            return size;
        }

        @Override public void revoke(String grantId) {
            if (grantId != null) grants.remove(grantId);
        }

        Permissions permissions(String url) { return grants.get(URI.create(url).getPath().substring(1)); }

        void put(String url, long bytes) {
            Permissions permissions = permissions(url);
            assertThat(permissions.access()).isEqualTo(Access.WRITE);
            objects.put(permissions.key(), bytes);
        }
    }

    @LocalServerPort int port;
    @MockitoBean ObjectStorageClient ociClient;
    @Autowired FakeStorage storage;
    @Autowired JdbcTemplate jdbc;
    @Autowired MediaJobs jobs;
    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient client = HttpClient.newHttpClient();

    @Test
    void recoveryTokenCanBeDisabledButCannotReuseAnotherRole() {
        var disabled = new MediaAccess(false, OWNER, WORKER, null);
        var denied = org.junit.jupiter.api.Assertions.assertThrows(ResponseStatusException.class,
                () -> disabled.requireRecovery("Bearer " + RECOVERY));
        assertThat(denied.getStatusCode()).isEqualTo(UNAUTHORIZED);
        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
                () -> new MediaAccess(false, OWNER, WORKER, WORKER));
    }

    @Test
    void ownerAndWorkerUseSeparateCredentialsAndScopedTransfers() throws Exception {
        Map<String, Object> video = Map.of("sizeBytes", 123, "contentType", "video/mp4");
        String root = "/media/jobs";
        send(401, root, null, video);
        send(401, root, WORKER, video);
        send(400, root, OWNER, Map.of("sizeBytes", 0, "contentType", "video/mp4"));
        send(400, root, OWNER, Map.of("sizeBytes", 123, "contentType", "text/html"));

        JsonNode started = body(send(200, root, OWNER, video));
        String id = started.path("jobId").asText();
        String ownerPath = root + "/" + id;
        String workerPath = "/internal" + ownerPath;
        String inputWrite = url(started.path("transfer"));
        String sourceKey = storage.permissions(inputWrite).key();
        assertThat(sourceKey).isEqualTo("inputs/" + id + "/source.mp4");
        send(409, workerPath + "/claim", WORKER, Map.of());
        send(409, ownerPath + "/preview", OWNER, Map.of());
        send(401, workerPath + "/claim", OWNER, Map.of());
        storage.put(inputWrite, 123);
        assertThat(body(send(200, ownerPath + "/input-complete", OWNER, Map.of())).path("status").asText())
                .isEqualTo("QUEUED");
        Long queuedAt = jdbc.queryForObject("SELECT queued_at FROM media_jobs WHERE id = ?", Long.class, id);
        Long availableAt = jdbc.queryForObject("SELECT available_at FROM media_jobs WHERE id = ?", Long.class, id);
        assertThat(queuedAt).isPositive();
        assertThat(availableAt).isEqualTo(queuedAt);
        assertThat(storage.permissions(inputWrite)).isNull();
        assertThat(body(send(200, ownerPath + "/input-complete", OWNER, Map.of())).path("status").asText())
                .isEqualTo("QUEUED");

        var claimed = body(send(200, workerPath + "/claim", WORKER, Map.of()));
        String lease = claimed.path("leaseId").asText();
        assertThat(storage.permissions(url(claimed.path("transfer"))))
                .isEqualTo(new FakeStorage.Permissions(sourceKey, MediaStorage.Access.READ));
        send(409, workerPath + "/claim", WORKER, Map.of());
        send(409, workerPath + "/result-upload", WORKER,
                Map.of("leaseId", UUID.randomUUID().toString(), "sizeBytes", 80));
        String firstResultUrl = url(body(send(200, workerPath + "/result-upload", WORKER,
                Map.of("leaseId", lease, "sizeBytes", 80))));
        send(409, workerPath + "/input-failed", WORKER,
                Map.of("leaseId", lease, "retryable", true));
        send(409, workerPath + "/processing-failed", WORKER,
                Map.of("leaseId", lease, "retryable", true));
        String previousKey = storage.permissions(firstResultUrl).key();
        storage.put(firstResultUrl, 79);
        send(409, workerPath + "/result-complete", WORKER, Map.of("leaseId", lease));
        send(409, ownerPath + "/preview", OWNER, Map.of());
        String resultUrl = url(body(send(200, workerPath + "/result-upload", WORKER,
                Map.of("leaseId", lease, "sizeBytes", 80))));
        String resultKey = storage.permissions(resultUrl).key();
        assertThat(resultKey).isNotEqualTo(previousKey).startsWith("outputs/" + id + "/").endsWith("/room.ply");
        storage.put(resultUrl, 80);
        send(200, workerPath + "/result-complete", WORKER, Map.of("leaseId", lease));
        send(200, workerPath + "/result-complete", WORKER, Map.of("leaseId", lease));
        send(409, workerPath + "/heartbeat", WORKER, Map.of("leaseId", lease));

        assertThat(storage.permissions(resultUrl)).isNull();
        assertThat(body(send(200, ownerPath, OWNER, null)).path("status").asText()).isEqualTo("RESULT_READY");
        send(401, ownerPath + "/preview", WORKER, Map.of());
        var preview = send(200, ownerPath + "/preview", OWNER, Map.of());
        assertThat(preview.headers().firstValue("Cache-Control")).contains("no-store");
        assertThat(storage.permissions(url(body(preview))))
                .isEqualTo(new FakeStorage.Permissions(resultKey, MediaStorage.Access.READ));
        org.junit.jupiter.api.Assertions.assertThrows(ResponseStatusException.class,
                () -> jobs.preview("another-member", UUID.fromString(id)));
    }

    @Test
    void incorrectlySizedInputDoesNotBecomeAJobTheWorkerCanClaim() throws Exception {
        var started = body(send(200, "/media/jobs", OWNER, Map.of("sizeBytes", 30, "contentType", "video/webm")));
        String id = started.path("jobId").asText();
        String inputWrite = url(started.path("transfer"));
        storage.put(inputWrite, 31);
        send(409, "/media/jobs/" + id + "/input-complete", OWNER, Map.of());
        assertThat(storage.permissions(inputWrite)).isNull();
        send(409, "/internal/media/jobs/" + id + "/claim", WORKER, Map.of());
        assertThat(jdbc.queryForObject("SELECT status FROM media_jobs WHERE id = ?", String.class, id)).isEqualTo("UPLOADING");
    }

    @Test
    void expiredProcessingLeaseCannotBeReclaimedWithoutRecovery() throws Exception {
        var started = body(send(200, "/media/jobs", OWNER, Map.of("sizeBytes", 30, "contentType", "video/mp4")));
        String id = started.path("jobId").asString();
        String ownerPath = "/media/jobs/" + id;
        String workerPath = "/internal" + ownerPath;
        storage.put(url(started.path("transfer")), 30);
        send(200, ownerPath + "/input-complete", OWNER, Map.of());

        String previousLease = body(send(200, workerPath + "/claim", WORKER, Map.of())).path("leaseId").asString();
        String previousResultUrl = url(body(send(200, workerPath + "/result-upload", WORKER,
                Map.of("leaseId", previousLease, "sizeBytes", 80))));
        storage.put(previousResultUrl, 80);
        jdbc.update("UPDATE media_jobs SET lease_expires = ? WHERE id = ?", 0L, id);
        send(409, workerPath + "/input-failed", WORKER,
                Map.of("leaseId", previousLease, "retryable", true));
        send(409, workerPath + "/claim", WORKER, Map.of());
        send(409, workerPath + "/claim", WORKER, Map.of("leaseId", previousLease));
        send(409, workerPath + "/claim", WORKER,
                Map.of("leaseId", UUID.randomUUID().toString()));
        send(409, workerPath + "/result-complete", WORKER, Map.of("leaseId", previousLease));
        assertThat(jdbc.queryForObject("SELECT status FROM media_jobs WHERE id = ?", String.class, id))
                .isEqualTo("PROCESSING");
        assertThat(jdbc.queryForObject("SELECT lease_id FROM media_jobs WHERE id = ?", String.class, id))
                .isEqualTo(previousLease);
        assertThat(jdbc.queryForObject("SELECT attempt_count FROM media_jobs WHERE id = ?", Integer.class, id))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT result_key FROM media_jobs WHERE id = ?", String.class, id))
                .isEqualTo(storage.permissions(previousResultUrl).key());
        send(409, ownerPath + "/preview", OWNER, Map.of());
    }

    @Test
    void confirmedExpiredExecutionCanBeRecoveredWithoutReusingItsResult() throws Exception {
        String id = queueVideo();
        String workerPath = "/internal/media/jobs/" + id;
        String lease = body(send(200, workerPath + "/claim", WORKER, Map.of())).path("leaseId").asText();
        String resultUrl = url(body(send(200, workerPath + "/result-upload", WORKER,
                Map.of("leaseId", lease, "sizeBytes", 80))));
        storage.put(resultUrl, 80);
        var confirmation = Map.of("leaseId", lease, "previousExecutionStopped", true, "retryable", true);

        send(401, workerPath + "/recover-expired", WORKER, confirmation);
        send(401, workerPath + "/recover-expired", OWNER, confirmation);
        send(400, workerPath + "/recover-expired", RECOVERY,
                Map.of("leaseId", lease, "previousExecutionStopped", false, "retryable", true));
        send(400, workerPath + "/recover-expired", RECOVERY,
                Map.of("leaseId", lease, "retryable", true));
        send(409, workerPath + "/recover-expired", RECOVERY, confirmation);
        assertThat(storage.permissions(resultUrl)).isNotNull();

        jdbc.update("UPDATE media_jobs SET lease_expires = 0 WHERE id = ?", id);
        send(409, workerPath + "/recover-expired", RECOVERY,
                Map.of("leaseId", UUID.randomUUID().toString(), "previousExecutionStopped", true, "retryable", true));
        long beforeRecovery = Instant.now().getEpochSecond();
        assertThat(body(send(200, workerPath + "/recover-expired", RECOVERY, confirmation))
                .path("status").asText()).isEqualTo("QUEUED");
        Long availableAt = jdbc.queryForObject("SELECT available_at FROM media_jobs WHERE id = ?", Long.class, id);
        assertThat(availableAt).isBetween(beforeRecovery + 30, beforeRecovery + 32);
        assertThat(storage.permissions(resultUrl)).isNull();
        assertThat(jdbc.queryForObject("SELECT result_key FROM media_jobs WHERE id = ?", String.class, id)).isNull();
        assertThat(jdbc.queryForObject("SELECT lease_id FROM media_jobs WHERE id = ?", String.class, id)).isNull();
        assertThat(jdbc.queryForObject("SELECT last_error_code FROM media_jobs WHERE id = ?", String.class, id))
                .isEqualTo("LEASE_EXPIRED");
        assertThat(jdbc.queryForObject("SELECT attempt_count FROM media_jobs WHERE id = ?", Integer.class, id))
                .isEqualTo(1);
        assertThat(body(send(200, workerPath + "/recover-expired", RECOVERY, confirmation))
                .path("status").asText()).isEqualTo("QUEUED");
        assertThat(jdbc.queryForObject("SELECT available_at FROM media_jobs WHERE id = ?", Long.class, id))
                .isEqualTo(availableAt);
        send(409, workerPath + "/claim", WORKER, Map.of());
        send(409, workerPath + "/result-complete", WORKER, Map.of("leaseId", lease));

        jdbc.update("UPDATE media_jobs SET available_at = 0 WHERE id = ?", id);
        String newLease = body(send(200, workerPath + "/claim", WORKER, Map.of())).path("leaseId").asText();
        assertThat(newLease).isNotEqualTo(lease);
        send(409, workerPath + "/recover-expired", RECOVERY, confirmation);
    }

    @Test
    void confirmedExpiredExecutionStopsAtAttemptLimit() throws Exception {
        String id = queueVideo();
        String workerPath = "/internal/media/jobs/" + id;
        for (int attempt = 1; attempt <= 3; attempt++) {
            String lease = body(send(200, workerPath + "/claim", WORKER, Map.of())).path("leaseId").asText();
            jdbc.update("UPDATE media_jobs SET lease_expires = 0 WHERE id = ?", id);
            String expected = attempt < 3 ? "QUEUED" : "FAILED";
            assertThat(body(send(200, workerPath + "/recover-expired", RECOVERY,
                    Map.of("leaseId", lease, "previousExecutionStopped", true, "retryable", true)))
                    .path("status").asText()).isEqualTo(expected);
            if (attempt < 3) {
                jdbc.update("UPDATE media_jobs SET available_at = 0 WHERE id = ?", id);
            }
        }
        assertThat(jdbc.queryForObject("SELECT attempt_count FROM media_jobs WHERE id = ?", Integer.class, id))
                .isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT available_at FROM media_jobs WHERE id = ?", Long.class, id)).isNull();
        send(409, workerPath + "/claim", WORKER, Map.of());
    }

    @Test
    void operatorCanFinishAnExpiredAttemptWithoutRetry() throws Exception {
        String id = queueVideo();
        String workerPath = "/internal/media/jobs/" + id;
        String lease = body(send(200, workerPath + "/claim", WORKER, Map.of())).path("leaseId").asText();
        jdbc.update("UPDATE media_jobs SET lease_expires = 0 WHERE id = ?", id);
        send(400, workerPath + "/recover-expired", RECOVERY,
                Map.of("leaseId", lease, "previousExecutionStopped", true));
        assertThat(body(send(200, workerPath + "/recover-expired", RECOVERY,
                Map.of("leaseId", lease, "previousExecutionStopped", true, "retryable", false)))
                .path("status").asText()).isEqualTo("FAILED");
        assertThat(jdbc.queryForObject("SELECT available_at FROM media_jobs WHERE id = ?", Long.class, id)).isNull();
        assertThat(body(send(200, "/media/jobs/" + id, OWNER, null)).path("status").asText())
                .isEqualTo("FAILED");
    }

    @Test
    void claimNextSelectsOldestAvailableJobAndCountsNewLeases() throws Exception {
        String delayed = queueVideo();
        String oldestReady = queueVideo();
        String newerReady = queueVideo();
        jdbc.update("UPDATE media_jobs SET queued_at = ?, available_at = ? WHERE id = ?", 50L,
                Instant.now().plusSeconds(3600).getEpochSecond(), delayed);
        jdbc.update("UPDATE media_jobs SET queued_at = ?, available_at = ? WHERE id = ?", 100L, 100L, oldestReady);
        jdbc.update("UPDATE media_jobs SET queued_at = ?, available_at = ? WHERE id = ?", 200L, 200L, newerReady);

        send(401, "/internal/media/jobs/claim-next", OWNER, Map.of());
        var first = body(send(200, "/internal/media/jobs/claim-next", WORKER, Map.of()));
        assertThat(first.path("jobId").asText()).isEqualTo(oldestReady);
        assertThat(jdbc.queryForObject("SELECT attempt_count FROM media_jobs WHERE id = ?", Integer.class, oldestReady))
                .isEqualTo(1);
        var resumed = body(send(200, "/internal/media/jobs/" + oldestReady + "/claim", WORKER,
                Map.of("leaseId", first.path("leaseId").asText())));
        assertThat(resumed.path("leaseId").asText()).isEqualTo(first.path("leaseId").asText());
        assertThat(jdbc.queryForObject("SELECT attempt_count FROM media_jobs WHERE id = ?", Integer.class, oldestReady))
                .isEqualTo(1);

        var second = body(send(200, "/internal/media/jobs/claim-next", WORKER, Map.of()));
        assertThat(second.path("jobId").asText()).isEqualTo(newerReady);
        send(204, "/internal/media/jobs/claim-next", WORKER, Map.of());
        jdbc.update("UPDATE media_jobs SET available_at = 0 WHERE id = ?", delayed);
        assertThat(body(send(200, "/internal/media/jobs/claim-next", WORKER, Map.of())).path("jobId").asText())
                .isEqualTo(delayed);
    }

    @Test
    void concurrentClaimNextRequestsDoNotReturnTheSameJob() throws Exception {
        String jobId = queueVideo();
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> {
                start.await();
                return requestClaimNext();
            });
            var second = executor.submit(() -> {
                start.await();
                return requestClaimNext();
            });
            start.countDown();
            var firstResponse = first.get();
            var secondResponse = second.get();
            assertThat(List.of(firstResponse.statusCode(), secondResponse.statusCode()))
                    .containsExactlyInAnyOrder(200, 204);
            var claimed = firstResponse.statusCode() == 200 ? firstResponse : secondResponse;
            assertThat(body(claimed).path("jobId").asText()).isEqualTo(jobId);
        }
        assertThat(jdbc.queryForObject("SELECT attempt_count FROM media_jobs WHERE id = ?", Integer.class, jobId))
                .isEqualTo(1);
    }

    @Test
    void retryableInputFailuresBackOffAndStopAfterThreeAttempts() throws Exception {
        String id = queueVideo();
        String workerPath = "/internal/media/jobs/" + id;
        String firstLease = body(send(200, workerPath + "/claim", WORKER, Map.of())).path("leaseId").asText();
        long beforeFirstFailure = Instant.now().getEpochSecond();
        assertThat(body(send(200, workerPath + "/input-failed", WORKER,
                Map.of("leaseId", firstLease, "retryable", true))).path("status").asText()).isEqualTo("QUEUED");
        assertThat(body(send(200, workerPath + "/input-failed", WORKER,
                Map.of("leaseId", firstLease, "retryable", true))).path("status").asText()).isEqualTo("QUEUED");
        assertThat(jdbc.queryForObject("SELECT available_at FROM media_jobs WHERE id = ?", Long.class, id))
                .isBetween(beforeFirstFailure + 30, beforeFirstFailure + 32);
        assertThat(jdbc.queryForObject("SELECT lease_id FROM media_jobs WHERE id = ?", String.class, id)).isNull();
        assertThat(jdbc.queryForObject("SELECT last_error_code FROM media_jobs WHERE id = ?", String.class, id))
                .isEqualTo("INPUT_DOWNLOAD_FAILED");
        send(409, workerPath + "/claim", WORKER, Map.of());
        send(409, workerPath + "/input-failed", WORKER,
                Map.of("leaseId", UUID.randomUUID().toString(), "retryable", true));

        jdbc.update("UPDATE media_jobs SET available_at = 0 WHERE id = ?", id);
        String secondLease = body(send(200, workerPath + "/claim", WORKER, Map.of())).path("leaseId").asText();
        send(409, workerPath + "/input-failed", WORKER,
                Map.of("leaseId", firstLease, "retryable", true));
        long beforeSecondFailure = Instant.now().getEpochSecond();
        assertThat(body(send(200, workerPath + "/input-failed", WORKER,
                Map.of("leaseId", secondLease, "retryable", true))).path("status").asText()).isEqualTo("QUEUED");
        assertThat(jdbc.queryForObject("SELECT available_at FROM media_jobs WHERE id = ?", Long.class, id))
                .isBetween(beforeSecondFailure + 60, beforeSecondFailure + 62);

        jdbc.update("UPDATE media_jobs SET available_at = 0 WHERE id = ?", id);
        String thirdLease = body(send(200, workerPath + "/claim", WORKER, Map.of())).path("leaseId").asText();
        assertThat(body(send(200, workerPath + "/input-failed", WORKER,
                Map.of("leaseId", thirdLease, "retryable", true))).path("status").asText()).isEqualTo("FAILED");
        assertThat(jdbc.queryForObject("SELECT attempt_count FROM media_jobs WHERE id = ?", Integer.class, id))
                .isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT available_at FROM media_jobs WHERE id = ?", Long.class, id)).isNull();
        send(409, workerPath + "/claim", WORKER, Map.of());
    }

    @Test
    void staleLeaseCannotClaimQueuedRetryAfterBackoff() throws Exception {
        String id = queueVideo();
        String workerPath = "/internal/media/jobs/" + id;
        String firstLease = body(send(200, workerPath + "/claim", WORKER, Map.of()))
                .path("leaseId").asText();
        send(200, workerPath + "/input-failed", WORKER,
                Map.of("leaseId", firstLease, "retryable", true));
        jdbc.update("UPDATE media_jobs SET available_at = 0 WHERE id = ?", id);

        send(409, workerPath + "/claim", WORKER, Map.of("leaseId", firstLease));
        assertThat(jdbc.queryForObject("SELECT status FROM media_jobs WHERE id = ?", String.class, id))
                .isEqualTo("QUEUED");
        assertThat(jdbc.queryForObject("SELECT lease_id FROM media_jobs WHERE id = ?", String.class, id))
                .isNull();
        assertThat(jdbc.queryForObject("SELECT attempt_count FROM media_jobs WHERE id = ?", Integer.class, id))
                .isEqualTo(1);

        String secondLease = body(send(200, workerPath + "/claim", WORKER, Map.of()))
                .path("leaseId").asText();
        assertThat(secondLease).isNotEqualTo(firstLease);
        assertThat(jdbc.queryForObject("SELECT attempt_count FROM media_jobs WHERE id = ?", Integer.class, id))
                .isEqualTo(2);
    }

    @Test
    void permanentInputFailureStopsAfterOneAttempt() throws Exception {
        String id = queueVideo();
        String workerPath = "/internal/media/jobs/" + id;
        String lease = body(send(200, workerPath + "/claim", WORKER, Map.of())).path("leaseId").asText();
        send(400, workerPath + "/input-failed", WORKER, Map.of("leaseId", lease));
        assertThat(body(send(200, workerPath + "/input-failed", WORKER,
                Map.of("leaseId", lease, "retryable", false))).path("status").asText()).isEqualTo("FAILED");
        assertThat(body(send(200, "/media/jobs/" + id, OWNER, null)).path("status").asText()).isEqualTo("FAILED");
        assertThat(jdbc.queryForObject("SELECT attempt_count FROM media_jobs WHERE id = ?", Integer.class, id))
                .isEqualTo(1);
        send(409, workerPath + "/result-upload", WORKER,
                Map.of("leaseId", lease, "sizeBytes", 80));
        send(409, workerPath + "/heartbeat", WORKER, Map.of("leaseId", lease));
    }

    @Test
    void registrationFailureRejectsAutomaticRetryThenRecordsReason() throws Exception {
        String id = queueVideo();
        String workerPath = "/internal/media/jobs/" + id;
        String lease = body(send(200, workerPath + "/claim", WORKER, Map.of())).path("leaseId").asText();
        var report = new java.util.HashMap<String, Object>(Map.of(
                "leaseId", lease, "retryable", true, "code", "INSUFFICIENT_REGISTERED_IMAGES",
                "matching", "sequential", "registeredImages", 2, "totalImages", 495));

        send(400, workerPath + "/processing-failed", WORKER, report);
        assertThat(jdbc.queryForObject("SELECT status FROM media_jobs WHERE id = ?", String.class, id))
                .isEqualTo("PROCESSING");
        assertThat(jdbc.queryForObject("SELECT last_error_code FROM media_jobs WHERE id = ?", String.class, id))
                .isNull();

        report.put("retryable", false);
        send(200, workerPath + "/processing-failed", WORKER, report);
        assertThat(jdbc.queryForObject("SELECT status FROM media_jobs WHERE id = ?", String.class, id))
                .isEqualTo("FAILED");
        assertThat(jdbc.queryForObject("SELECT last_error_code FROM media_jobs WHERE id = ?", String.class, id))
                .isEqualTo("INSUFFICIENT_REGISTERED_IMAGES");
    }

    @Test
    void processingFailureUsesCurrentLeaseAndRetryPolicy() throws Exception {
        String id = queueVideo();
        String workerPath = "/internal/media/jobs/" + id;
        send(401, workerPath + "/processing-failed", OWNER,
                Map.of("leaseId", UUID.randomUUID().toString(), "retryable", true));
        String firstLease = body(send(200, workerPath + "/claim", WORKER, Map.of())).path("leaseId").asText();
        send(400, workerPath + "/processing-failed", WORKER, Map.of("leaseId", firstLease));
        send(409, workerPath + "/processing-failed", WORKER,
                Map.of("leaseId", UUID.randomUUID().toString(), "retryable", true));

        long beforeFailure = Instant.now().getEpochSecond();
        assertThat(body(send(200, workerPath + "/processing-failed", WORKER,
                Map.of("leaseId", firstLease, "retryable", true))).path("status").asText()).isEqualTo("QUEUED");
        assertThat(body(send(200, workerPath + "/processing-failed", WORKER,
                Map.of("leaseId", firstLease, "retryable", true))).path("status").asText()).isEqualTo("QUEUED");
        assertThat(jdbc.queryForObject("SELECT available_at FROM media_jobs WHERE id = ?", Long.class, id))
                .isBetween(beforeFailure + 30, beforeFailure + 32);
        assertThat(jdbc.queryForObject("SELECT last_error_code FROM media_jobs WHERE id = ?", String.class, id))
                .isEqualTo("PROCESSING_FAILED");
        assertThat(jdbc.queryForObject("SELECT lease_id FROM media_jobs WHERE id = ?", String.class, id)).isNull();
        send(409, workerPath + "/input-failed", WORKER,
                Map.of("leaseId", firstLease, "retryable", true));

        jdbc.update("UPDATE media_jobs SET available_at = 0 WHERE id = ?", id);
        String secondLease = body(send(200, workerPath + "/claim", WORKER, Map.of())).path("leaseId").asText();
        send(409, workerPath + "/processing-failed", WORKER,
                Map.of("leaseId", firstLease, "retryable", true));
        assertThat(body(send(200, workerPath + "/processing-failed", WORKER,
                Map.of("leaseId", secondLease, "retryable", false))).path("status").asText()).isEqualTo("FAILED");
        assertThat(jdbc.queryForObject("SELECT attempt_count FROM media_jobs WHERE id = ?", Integer.class, id))
                .isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT available_at FROM media_jobs WHERE id = ?", Long.class, id)).isNull();
        assertThat(body(send(200, "/media/jobs/" + id, OWNER, null)).path("status").asText()).isEqualTo("FAILED");
    }

    @Test
    void expiredProcessingLeaseCannotReportFailure() throws Exception {
        String id = queueVideo();
        String workerPath = "/internal/media/jobs/" + id;
        String lease = body(send(200, workerPath + "/claim", WORKER, Map.of())).path("leaseId").asText();
        jdbc.update("UPDATE media_jobs SET lease_expires = 0 WHERE id = ?", id);
        send(409, workerPath + "/processing-failed", WORKER,
                Map.of("leaseId", lease, "retryable", true));
        assertThat(jdbc.queryForObject("SELECT status FROM media_jobs WHERE id = ?", String.class, id))
                .isEqualTo("PROCESSING");
    }

    @Test
    void heartbeatExtendsOnlyTheCurrentActiveLease() throws Exception {
        String id = queueVideo();
        String workerPath = "/internal/media/jobs/" + id;
        send(401, workerPath + "/heartbeat", OWNER, Map.of());
        send(409, workerPath + "/heartbeat", WORKER, Map.of());
        String lease = body(send(200, workerPath + "/claim", WORKER, Map.of())).path("leaseId").asText();
        send(409, workerPath + "/heartbeat", WORKER,
                Map.of("leaseId", UUID.randomUUID().toString()));

        long oldExpiry = Instant.now().plusSeconds(5).getEpochSecond();
        jdbc.update("UPDATE media_jobs SET lease_expires = ? WHERE id = ?", oldExpiry, id);
        var renewed = body(send(200, workerPath + "/heartbeat", WORKER, Map.of("leaseId", lease)));
        long renewedExpiry = Instant.parse(renewed.path("leaseExpiresAt").asText()).getEpochSecond();
        assertThat(renewedExpiry).isGreaterThan(oldExpiry);
        assertThat(jdbc.queryForObject("SELECT lease_expires FROM media_jobs WHERE id = ?", Long.class, id))
                .isEqualTo(renewedExpiry);
        assertThat(jdbc.queryForObject("SELECT attempt_count FROM media_jobs WHERE id = ?", Integer.class, id))
                .isEqualTo(1);
        assertThat(renewed.has("leaseId")).isFalse();

        long laterExpiry = Instant.now().plusSeconds(7 * 3600).getEpochSecond();
        jdbc.update("UPDATE media_jobs SET lease_expires = ? WHERE id = ?", laterExpiry, id);
        var repeated = body(send(200, workerPath + "/heartbeat", WORKER, Map.of("leaseId", lease)));
        assertThat(Instant.parse(repeated.path("leaseExpiresAt").asText()).getEpochSecond()).isEqualTo(laterExpiry);

        jdbc.update("UPDATE media_jobs SET lease_expires = 0 WHERE id = ?", id);
        send(409, workerPath + "/heartbeat", WORKER, Map.of("leaseId", lease));
        assertThat(jdbc.queryForObject("SELECT lease_expires FROM media_jobs WHERE id = ?", Long.class, id))
                .isZero();
    }

    private String queueVideo() throws Exception {
        var started = body(send(200, "/media/jobs", OWNER, Map.of("sizeBytes", 30, "contentType", "video/mp4")));
        String id = started.path("jobId").asText();
        storage.put(url(started.path("transfer")), 30);
        send(200, "/media/jobs/" + id + "/input-complete", OWNER, Map.of());
        return id;
    }

    private HttpResponse<String> requestClaimNext() throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/internal/media/jobs/claim-next"))
                .header("Authorization", "Bearer " + WORKER)
                .POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> send(int status, String path, String token, Object data) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api" + path));
        if (token != null) request.header("Authorization", "Bearer " + token);
        if (data != null) request.header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(data)));
        var response = client.send(request.build(), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as(path + ": " + response.body()).isEqualTo(status);
        return response;
    }

    private JsonNode body(HttpResponse<String> response) { return json.readTree(response.body()); }
    private String url(JsonNode transfer) { return transfer.path("url").asText(); }
}
