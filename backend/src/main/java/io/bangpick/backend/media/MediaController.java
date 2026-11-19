package io.bangpick.backend.media;

import io.bangpick.backend.auth.MemberPrincipal;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
@ConditionalOnProperty(name = "bangpick.media.enabled", havingValue = "true")
class MediaController {
    private final MediaAccess access;
    private final MediaJobs jobs;

    MediaController(MediaAccess access, MediaJobs jobs) {
        this.access = access;
        this.jobs = jobs;
    }

    record UploadRequest(String contentType, Long sizeBytes) {}
    record LeaseRequest(UUID leaseId) {
        @Override public String toString() { return "LeaseRequest[redacted]"; }
    }
    record ResultRequest(UUID leaseId, Long sizeBytes) {
        @Override public String toString() { return "ResultRequest[sizeBytes=" + sizeBytes + "]"; }
    }

    @PostMapping("/media/jobs")
    ResponseEntity<MediaJobs.Upload> start(@AuthenticationPrincipal MemberPrincipal principal,
                                          @RequestHeader(value = "Authorization", required = false) String token,
                                          @RequestBody UploadRequest request) {
        return noStore(jobs.start(access.requireOwner(principal, token), request.contentType(), request.sizeBytes()));
    }

    @PostMapping("/media/jobs/{jobId}/input-complete")
    ResponseEntity<MediaJobs.JobView> inputComplete(@AuthenticationPrincipal MemberPrincipal principal,
                                                    @RequestHeader(value = "Authorization", required = false) String token,
                                                    @PathVariable UUID jobId) {
        return noStore(jobs.finishInput(access.requireOwner(principal, token), jobId));
    }

    @GetMapping("/media/jobs/{jobId}")
    ResponseEntity<MediaJobs.JobView> status(@AuthenticationPrincipal MemberPrincipal principal,
                                             @RequestHeader(value = "Authorization", required = false) String token,
                                             @PathVariable UUID jobId) {
        return noStore(jobs.status(access.requireOwner(principal, token), jobId));
    }

    @PostMapping("/media/jobs/{jobId}/preview")
    ResponseEntity<MediaJobs.Transfer> preview(@AuthenticationPrincipal MemberPrincipal principal,
                                                @RequestHeader(value = "Authorization", required = false) String token,
                                                @PathVariable UUID jobId) {
        return noStore(jobs.preview(access.requireOwner(principal, token), jobId));
    }

    @PostMapping("/internal/media/jobs/{jobId}/claim")
    ResponseEntity<MediaJobs.Claim> claim(@RequestHeader(value = "Authorization", required = false) String token,
                                          @PathVariable UUID jobId, @RequestBody LeaseRequest request) {
        access.requireWorker(token);
        return noStore(jobs.claim(jobId, request.leaseId()));
    }

    @PostMapping("/internal/media/jobs/{jobId}/result-upload")
    ResponseEntity<MediaJobs.Transfer> resultUpload(@RequestHeader(value = "Authorization", required = false) String token,
                                                     @PathVariable UUID jobId, @RequestBody ResultRequest request) {
        access.requireWorker(token);
        return noStore(jobs.startResult(jobId, request.leaseId(), request.sizeBytes()));
    }

    @PostMapping("/internal/media/jobs/{jobId}/result-complete")
    ResponseEntity<MediaJobs.JobView> resultComplete(@RequestHeader(value = "Authorization", required = false) String token,
                                                     @PathVariable UUID jobId, @RequestBody LeaseRequest request) {
        access.requireWorker(token);
        return noStore(jobs.finishResult(jobId, request.leaseId()));
    }

    private static <T> ResponseEntity<T> noStore(T body) {
        // 임시 전송 링크가 브라우저나 프록시 캐시에 남지 않게 한다.
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
    }
}
