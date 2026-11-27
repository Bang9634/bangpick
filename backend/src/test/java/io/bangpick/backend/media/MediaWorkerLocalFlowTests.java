package io.bangpick.backend.media;

import static org.assertj.core.api.Assertions.assertThat;

import com.oracle.bmc.objectstorage.ObjectStorageClient;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;
import io.bangpick.backend.BangpickBackendApplication;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.security.cert.Certificate;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Opt-in flow through the real backend API and Python Worker with a local HTTPS object store. */
@EnabledIfEnvironmentVariable(named = "BANGPICK_WORKER_E2E_PYTHON", matches = ".+")
@SpringBootTest(classes = BangpickBackendApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "bangpick.media.enabled=true", "spring.flyway.enabled=true",
        "bangpick.auth.enabled=false",
        "bangpick.media.dev-token=" + MediaWorkerLocalFlowTests.OWNER,
        "bangpick.media.worker-token=" + MediaWorkerLocalFlowTests.WORKER,
        "spring.datasource.url=jdbc:h2:mem:mediaworkerflow;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa", "spring.datasource.password="
})
@Import(MediaWorkerLocalFlowTests.StorageConfiguration.class)
class MediaWorkerLocalFlowTests {
    static final String OWNER = "example-development-token-for-tests-123";
    static final String WORKER = "example-worker-token-for-tests-456";
    private static final byte[] INPUT = "synthetic-transfer-input".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
    private static final byte[] PLY = "ply\nformat ascii 1.0\nend_header\n"
            .getBytes(java.nio.charset.StandardCharsets.US_ASCII);

    @org.springframework.boot.test.context.TestConfiguration(proxyBeanMethods = false)
    static class StorageConfiguration {
        @Bean @org.springframework.context.annotation.Primary
        LocalObjectStore storage() throws Exception { return new LocalObjectStore(); }
    }

    @MockitoBean ObjectStorageClient ociClient;
    @LocalServerPort int port;
    @Autowired LocalObjectStore storage;
    @TempDir Path workDir;
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void uploadedInputReachesWorkerAndResultReturnsThroughPreview() throws Exception {
        HttpClient client = HttpClient.newBuilder().sslContext(storage.trustContext).build();
        JsonNode created = body(send(client, "POST", "/media/jobs", OWNER,
                "{\"contentType\":\"video/mp4\",\"sizeBytes\":" + INPUT.length + "}"), 200);
        String jobId = created.path("jobId").asText();
        String inputUrl = created.path("transfer").path("url").asText();
        assertThat(client.send(HttpRequest.newBuilder(URI.create(inputUrl))
                .PUT(HttpRequest.BodyPublishers.ofByteArray(INPUT)).build(), HttpResponse.BodyHandlers.discarding())
                .statusCode()).isEqualTo(200);
        assertThat(body(send(client, "POST", "/media/jobs/" + jobId + "/input-complete", OWNER, null), 200)
                .path("status").asText()).isEqualTo("QUEUED");

        Path python = Path.of(System.getenv("BANGPICK_WORKER_E2E_PYTHON"));
        Path source = Path.of("..", "gpu-worker", "src").toAbsolutePath().normalize();
        assertThat(python).isRegularFile();
        assertThat(source).isDirectory();
        String processor = "import os; from pathlib import Path; "
                + "assert Path(os.environ['BANGPICK_INPUT_VIDEO']).read_bytes() == " + repr(INPUT) + "; "
                + "Path(os.environ['BANGPICK_OUTPUT_PLY']).write_bytes(" + repr(PLY) + ")";
        var command = new ProcessBuilder(python.toString(), "-m", "bangpick_worker", "process-next",
                "--work-dir", workDir.toString(), "--", python.toString(), "-c", processor);
        command.redirectErrorStream(true);
        command.environment().put("PYTHONPATH", source.toString());
        command.environment().put("SSL_CERT_FILE", storage.certificate.toString());
        command.environment().put("BANGPICK_API_URL", "http://127.0.0.1:" + port + "/api");
        command.environment().put("BANGPICK_MEDIA_WORKER_TOKEN", WORKER);
        Process worker = command.start();
        if (!worker.waitFor(30, TimeUnit.SECONDS)) {
            worker.destroyForcibly();
            worker.waitFor();
            throw new AssertionError("Worker did not finish within 30 seconds");
        }
        String workerOutput = new String(worker.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        assertThat(worker.exitValue()).as(workerOutput).isZero();
        assertThat(workerOutput).contains("Claimed job " + jobId, "Result recorded for job " + jobId);

        assertThat(body(send(client, "GET", "/media/jobs/" + jobId, OWNER, null), 200)
                .path("status").asText()).isEqualTo("RESULT_READY");
        String previewUrl = body(send(client, "POST", "/media/jobs/" + jobId + "/preview", OWNER, null), 200)
                .path("url").asText();
        HttpResponse<byte[]> preview = client.send(HttpRequest.newBuilder(URI.create(previewUrl)).GET().build(),
                HttpResponse.BodyHandlers.ofByteArray());
        assertThat(preview.statusCode()).isEqualTo(200);
        assertThat(preview.body()).isEqualTo(PLY);
    }

    private HttpResponse<String> send(HttpClient client, String method, String path, String token, String data)
            throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api" + path))
                .header("Authorization", "Bearer " + token);
        if ("POST".equals(method)) {
            request.header("Content-Type", "application/json")
                    .POST(data == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(data));
        } else {
            request.GET();
        }
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private JsonNode body(HttpResponse<String> response, int status) {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(status);
        return json.readTree(response.body());
    }

    private static String repr(byte[] bytes) {
        StringBuilder value = new StringBuilder("b'");
        for (byte item : bytes) {
            int unsigned = Byte.toUnsignedInt(item);
            if (unsigned == '\n') value.append("\\n");
            else if (unsigned == '\\') value.append("\\\\");
            else if (unsigned == '\'') value.append("\\'");
            else if (unsigned >= 32 && unsigned < 127) value.append((char) unsigned);
            else value.append("\\x%02x".formatted(unsigned));
        }
        return value.append('\'').toString();
    }

    static class LocalObjectStore implements MediaStorage, AutoCloseable {
        record Permission(String key, Access access, Instant expiresAt) {}

        private static final char[] PASSWORD = "local-test-only".toCharArray();
        private final Map<String, Permission> grants = new ConcurrentHashMap<>();
        private final Map<String, byte[]> objects = new ConcurrentHashMap<>();
        private final Path directory;
        final Path certificate;
        final SSLContext trustContext;
        private final HttpsServer server;

        LocalObjectStore() throws Exception {
            directory = Files.createTempDirectory("bangpick-local-store-");
            Path keystore = directory.resolve("store.p12");
            certificate = directory.resolve("certificate.pem");
            Path keytool = Path.of(System.getProperty("java.home"), "bin",
                    System.getProperty("os.name").startsWith("Windows") ? "keytool.exe" : "keytool");
            runKeytool(keytool, "-genkeypair", "-alias", "local", "-keyalg", "RSA", "-keysize", "2048",
                    "-storetype", "PKCS12", "-keystore", keystore.toString(), "-storepass", String.valueOf(PASSWORD),
                    "-keypass", String.valueOf(PASSWORD), "-dname", "CN=127.0.0.1", "-validity", "2",
                    "-ext", "SAN=ip:127.0.0.1,dns:localhost", "-noprompt");
            runKeytool(keytool, "-exportcert", "-rfc", "-alias", "local", "-keystore", keystore.toString(),
                    "-storepass", String.valueOf(PASSWORD), "-file", certificate.toString());
            KeyStore keys = KeyStore.getInstance("PKCS12");
            try (var input = Files.newInputStream(keystore)) { keys.load(input, PASSWORD); }
            KeyManagerFactory managers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            managers.init(keys, PASSWORD);
            SSLContext serverContext = SSLContext.getInstance("TLS");
            serverContext.init(managers.getKeyManagers(), null, new SecureRandom());
            KeyStore trusted = KeyStore.getInstance("PKCS12");
            trusted.load(null, null);
            Certificate cert = keys.getCertificate("local");
            trusted.setCertificateEntry("local", cert);
            TrustManagerFactory trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            trust.init(trusted);
            trustContext = SSLContext.getInstance("TLS");
            trustContext.init(null, trust.getTrustManagers(), new SecureRandom());
            server = HttpsServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.setHttpsConfigurator(new HttpsConfigurator(serverContext));
            server.createContext("/grants/", this::transfer);
            server.start();
        }

        @Override public Grant grant(String key, Access access, Instant expiresAt) {
            String id = UUID.randomUUID().toString();
            grants.put(id, new Permission(key, access, expiresAt));
            return new Grant(id, URI.create("https://127.0.0.1:" + server.getAddress().getPort() + "/grants/" + id));
        }

        @Override public long size(String key) {
            byte[] bytes = objects.get(key);
            if (bytes == null) throw new ResponseStatusException(org.springframework.http.HttpStatus.CONFLICT);
            return bytes.length;
        }

        @Override public void revoke(String grantId) {
            if (grantId != null) grants.remove(grantId);
        }

        private void transfer(HttpExchange exchange) throws IOException {
            try (exchange) {
                String id = exchange.getRequestURI().getPath().substring("/grants/".length());
                Permission permission = grants.get(id);
                if (permission == null || !permission.expiresAt().isAfter(Instant.now())) {
                    exchange.sendResponseHeaders(403, -1);
                    return;
                }
                if ("PUT".equals(exchange.getRequestMethod()) && permission.access() == Access.WRITE) {
                    objects.put(permission.key(), exchange.getRequestBody().readAllBytes());
                    exchange.sendResponseHeaders(200, -1);
                } else if ("GET".equals(exchange.getRequestMethod()) && permission.access() == Access.READ) {
                    byte[] bytes = objects.get(permission.key());
                    if (bytes == null) {
                        exchange.sendResponseHeaders(404, -1);
                    } else {
                        exchange.sendResponseHeaders(200, bytes.length);
                        exchange.getResponseBody().write(bytes);
                    }
                } else {
                    exchange.sendResponseHeaders(405, -1);
                }
            }
        }

        private static void runKeytool(Path keytool, String... args) throws Exception {
            var command = new java.util.ArrayList<String>();
            command.add(keytool.toString());
            command.addAll(java.util.List.of(args));
            Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
            if (!process.waitFor(15, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new AssertionError("Could not create the local TLS certificate");
            }
            assertThat(process.exitValue()).as("Could not create the local TLS certificate").isZero();
        }

        @Override public void close() throws IOException {
            server.stop(0);
            Files.deleteIfExists(certificate);
            Files.deleteIfExists(directory.resolve("store.p12"));
            Files.deleteIfExists(directory);
        }
    }
}
