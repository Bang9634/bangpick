package io.bangpick.backend.media;

import static org.springframework.http.HttpStatus.BAD_GATEWAY;
import static org.springframework.http.HttpStatus.CONFLICT;

import com.oracle.bmc.model.BmcException;
import com.oracle.bmc.objectstorage.ObjectStorageClient;
import com.oracle.bmc.objectstorage.model.CreatePreauthenticatedRequestDetails;
import com.oracle.bmc.objectstorage.model.CreatePreauthenticatedRequestDetails.AccessType;
import com.oracle.bmc.objectstorage.requests.CreatePreauthenticatedRequestRequest;
import com.oracle.bmc.objectstorage.requests.DeletePreauthenticatedRequestRequest;
import com.oracle.bmc.objectstorage.requests.HeadObjectRequest;
import java.net.URI;
import java.time.Instant;
import java.util.Date;
import java.util.Map;
import java.util.UUID;
import org.springframework.web.server.ResponseStatusException;

final class OciMediaStorage implements MediaStorage {
    private final ObjectStorageClient client;
    private final String namespace;
    private final String bucket;

    OciMediaStorage(ObjectStorageClient client, String namespace, String bucket) {
        this.client = client;
        this.namespace = namespace;
        this.bucket = bucket;
    }

    @Override
    public Grant grant(String key, Access access, Instant expiresAt) {
        try {
            // SDK의 기본 주소는 치환 전 템플릿일 수 있으므로 완성된 주소를 사용한다.
            var endpoint = URI.create(client.getResolvedEndpoint(Map.of("namespaceName", namespace)));
            var details = CreatePreauthenticatedRequestDetails.builder()
                    .name("bangpick-" + UUID.randomUUID())
                    .objectName(key)
                    .accessType(access == Access.READ ? AccessType.ObjectRead : AccessType.ObjectWrite)
                    .timeExpires(Date.from(expiresAt))
                    .build();
            var par = client.createPreauthenticatedRequest(CreatePreauthenticatedRequestRequest.builder()
                    .namespaceName(namespace).bucketName(bucket)
                    .createPreauthenticatedRequestDetails(details).build()).getPreauthenticatedRequest();
            String accessUri = par.getAccessUri();
            if (accessUri == null || !accessUri.startsWith("/p/")) throw unavailable();
            return new Grant(par.getId(), endpoint.resolve(accessUri));
        } catch (BmcException | IllegalArgumentException ex) {
            throw unavailable();
        }
    }

    @Override
    public long size(String key) {
        try {
            return client.headObject(HeadObjectRequest.builder()
                    .namespaceName(namespace).bucketName(bucket).objectName(key).build()).getContentLength();
        } catch (BmcException ex) {
            if (ex.getStatusCode() == 404) {
                throw new ResponseStatusException(CONFLICT, "Uploaded object could not be verified");
            }
            throw unavailable();
        }
    }

    @Override
    public void revoke(String grantId) {
        if (grantId == null) return;
        try {
            client.deletePreauthenticatedRequest(DeletePreauthenticatedRequestRequest.builder()
                    .namespaceName(namespace).bucketName(bucket).parId(grantId).build());
        } catch (BmcException ex) {
            if (ex.getStatusCode() != 404) throw unavailable();
        }
    }

    private static ResponseStatusException unavailable() {
        // SDK 오류 메시지에 접속 정보나 임시 링크가 포함될 수 있어 외부로 전달하지 않는다.
        return new ResponseStatusException(BAD_GATEWAY, "Object storage request failed");
    }
}
