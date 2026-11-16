package io.bangpick.backend.media;

import static org.assertj.core.api.Assertions.assertThat;

import com.oracle.bmc.auth.SimpleAuthenticationDetailsProvider;
import com.oracle.bmc.objectstorage.ObjectStorageClient;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayInputStream;
import java.net.InetSocketAddress;
import java.security.KeyPairGenerator;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;

class OciMediaStorageTests {
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void usesScopedOciGrantsAndQueriesOnlyMetadata(boolean templatedEndpoint) throws Exception {
        var generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        String pem = "-----BEGIN PRIVATE KEY-----\n"
                + Base64.getMimeEncoder().encodeToString(generator.generateKeyPair().getPrivate().getEncoded())
                + "\n-----END PRIVATE KEY-----\n";
        var auth = SimpleAuthenticationDetailsProvider.builder()
                .tenantId("ocid1.tenancy.oc1..example").userId("ocid1.user.oc1..example")
                .fingerprint("00:11:22:33:44:55:66:77:88:99:aa:bb:cc:dd:ee:ff")
                .privateKeySupplier(() -> new ByteArrayInputStream(pem.getBytes(java.nio.charset.StandardCharsets.US_ASCII)))
                .build();

        String namespace = "127.0.0.1";
        var server = HttpServer.create(new InetSocketAddress(namespace, 0), 0);
        var requests = Collections.synchronizedList(new ArrayList<String>());
        var bodies = Collections.synchronizedList(new ArrayList<byte[]>());
        server.createContext("/", exchange -> {
            try (exchange) {
                requests.add(exchange.getRequestMethod() + " " + exchange.getRequestURI().getPath());
                if (exchange.getRequestMethod().equals("POST")) {
                    bodies.add(exchange.getRequestBody().readAllBytes());
                    byte[] response = """
                            {"id":"grant-1","accessUri":"/p/fake/n/%s/b/dev/o/outputs/job/room.ply"}
                            """.formatted(namespace).getBytes(java.nio.charset.StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.sendResponseHeaders(200, response.length);
                    exchange.getResponseBody().write(response);
                } else if (exchange.getRequestMethod().equals("HEAD")) {
                    exchange.getResponseHeaders().set("Content-Length", "42");
                    exchange.sendResponseHeaders(200, -1);
                } else {
                    exchange.sendResponseHeaders(204, -1);
                }
            }
        });
        server.start();
        String endpoint = "http://" + namespace + ":" + server.getAddress().getPort();
        String configuredEndpoint = templatedEndpoint ? endpoint.replace(namespace, "{namespaceName}") : endpoint;
        try (var client = ObjectStorageClient.builder().endpoint(configuredEndpoint).build(auth)) {
            var storage = new OciMediaStorage(client, namespace, "dev");
            String key = "outputs/job/room.ply";
            var grant = storage.grant(key, MediaStorage.Access.WRITE, Instant.now().plusSeconds(60));
            assertThat(grant.url().toString()).isEqualTo(endpoint + "/p/fake/n/" + namespace + "/b/dev/o/" + key);
            assertThat(storage.size(key)).isEqualTo(42);
            storage.revoke(grant.id());

            var result = JsonMapper.builder().build().readTree(bodies.getFirst());
            assertThat(result.path("objectName").asString()).isEqualTo(key);
            assertThat(result.path("accessType").asString()).isEqualTo("ObjectWrite");
            String bucketPath = "/n/" + namespace + "/b/dev";
            assertThat(requests).isEqualTo(List.of(
                    "POST " + bucketPath + "/p", "HEAD " + bucketPath + "/o/" + key,
                    "DELETE " + bucketPath + "/p/grant-1"));
        } finally {
            server.stop(0);
        }
    }
}
