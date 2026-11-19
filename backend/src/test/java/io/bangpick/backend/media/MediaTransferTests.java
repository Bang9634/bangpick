package io.bangpick.backend.media;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.HttpStatus.CONFLICT;

import com.oracle.bmc.objectstorage.ObjectStorageClient;
import io.bangpick.backend.BangpickBackendApplication;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
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
        "spring.datasource.url=jdbc:h2:mem:mediaflow;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa", "spring.datasource.password="
})
@Import(MediaTransferTests.StorageConfiguration.class)
class MediaTransferTests {
    static final String OWNER = "example-development-token-for-tests-123";
    static final String WORKER = "example-worker-token-for-tests-456";

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
    void reclaimingAnExpiredLeaseDiscardsThePreviousResultGrant() throws Exception {
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

        String newLease = body(send(200, workerPath + "/claim", WORKER, Map.of())).path("leaseId").asString();
        assertThat(newLease).isNotEqualTo(previousLease);
        assertThat(storage.permissions(previousResultUrl)).isNull();
        send(409, workerPath + "/result-complete", WORKER, Map.of("leaseId", previousLease));
        send(409, workerPath + "/result-complete", WORKER, Map.of("leaseId", newLease));
        send(409, ownerPath + "/preview", OWNER, Map.of());
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
