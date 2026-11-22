package io.bangpick.backend.media;

import static io.bangpick.backend.media.MediaStorage.Access.READ;
import static io.bangpick.backend.media.MediaStorage.Access.WRITE;
import static org.springframework.http.HttpStatus.BAD_REQUEST;
import static org.springframework.http.HttpStatus.CONFLICT;
import static org.springframework.http.HttpStatus.NOT_FOUND;

import io.bangpick.backend.media.MediaJob.Status;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

class MediaJobs {
    private static final long MAX_VIDEO = 512L * 1024 * 1024;
    private static final long MAX_PLY = 256L * 1024 * 1024;
    private static final Duration LEASE_DURATION = Duration.ofHours(6);
    private static final Duration READ_GRANT_DURATION = Duration.ofMinutes(5);
    private static final Duration WRITE_GRANT_DURATION = Duration.ofMinutes(15);
    private static final int MAX_ATTEMPTS = 3;
    private static final Duration RETRY_DELAY = Duration.ofSeconds(30);
    private static final Map<String, String> VIDEO_EXTENSIONS = Map.of(
            "video/mp4", ".mp4", "video/quicktime", ".mov", "video/webm", ".webm");

    record Transfer(URI url, String method, Instant expiresAt, long sizeBytes) {
        @Override public String toString() { return "Transfer[method=" + method + ", sizeBytes=" + sizeBytes + "]"; }
    }
    record Upload(UUID jobId, Transfer transfer) {}
    record Claim(UUID leaseId, Instant leaseExpiresAt, String extension, Transfer transfer) {
        @Override public String toString() { return "Claim[leaseExpiresAt=" + leaseExpiresAt + "]"; }
    }
    record NextClaim(UUID jobId, UUID leaseId, Instant leaseExpiresAt) {
        @Override public String toString() { return "NextClaim[jobId=" + jobId + ", leaseExpiresAt=" + leaseExpiresAt + "]"; }
    }
    record LeaseHeartbeat(Instant leaseExpiresAt) {}
    record JobView(UUID jobId, Status status) {}

    private final MediaJobRepository repository;
    private final MediaStorage storage;

    MediaJobs(MediaJobRepository repository, MediaStorage storage) {
        this.repository = repository;
        this.storage = storage;
    }

    @Transactional
    public Upload start(String owner, String type, Long bytes) {
        if (type == null || !VIDEO_EXTENSIONS.containsKey(type)) {
            throw new ResponseStatusException(BAD_REQUEST, "Choose an MP4, MOV or WebM video");
        }
        checkSize(bytes, MAX_VIDEO);
        UUID id = UUID.randomUUID();
        Instant expires = Instant.now().plus(WRITE_GRANT_DURATION);
        String key = inputKey(id, type);
        var grant = storage.grant(key, WRITE, expires);
        repository.insertUpload(id, owner, type, bytes, grant.id(), expires);
        return new Upload(id, new Transfer(grant.url(), "PUT", expires, bytes));
    }

    @Transactional
    public JobView finishInput(String owner, UUID id) {
        MediaJob job = owned(loadForUpdate(id), owner);
        if (job.status() != Status.UPLOADING) return view(job);
        if (!job.uploadExpiresAt().isAfter(Instant.now())) throw conflict("Upload grant expired; start a new upload");
        // 크기 확인 이후 같은 링크로 새 PUT 요청을 보낼 수 없도록 먼저 쓰기 권한을 회수한다.
        storage.revoke(job.inputGrantId());
        verifySize(inputKey(id, job.videoType()), job.videoBytes());
        repository.markInputQueued(id, Instant.now());
        return new JobView(id, Status.QUEUED);
    }

    public JobView status(String owner, UUID id) {
        return view(owned(load(id), owner));
    }

    public Transfer preview(String owner, UUID id) {
        MediaJob job = owned(load(id), owner);
        if (job.status() != Status.RESULT_READY) throw conflict("The PLY result is not ready");
        // RESULT_READY는 저장 확인 상태다. 이 링크는 소유자 전용 미리보기에 쓴다.
        return grant(job.resultKey(), READ, Instant.now().plus(READ_GRANT_DURATION), job.resultBytes());
    }

    /** 작업 한 건을 점유하고 ID를 반환한다. 점유는 GPU 실행 시작이나 전역 GPU 용량 확보를 뜻하지 않는다. */
    @Transactional
    public Optional<NextClaim> claimNext() {
        Instant now = Instant.now();
        return repository.findNextQueuedForUpdate(now, MAX_ATTEMPTS).map(id -> {
            UUID lease = UUID.randomUUID();
            Instant leaseExpires = now.plus(LEASE_DURATION).truncatedTo(ChronoUnit.SECONDS);
            repository.assignLease(id, lease, leaseExpires);
            return new NextClaim(id, lease, leaseExpires);
        });
    }

    @Transactional
    public Claim claim(UUID id, UUID currentLease) {
        MediaJob job = loadForUpdate(id);
        Instant now = Instant.now();
        if (job.hasActiveLease(now)) {
            // 같은 점유 ID로 다시 요청하면 읽기 링크만 새로 발급한다.
            requireLease(job, currentLease);
            return inputGrant(job, currentLease, job.leaseExpiresAt(), now);
        }
        // 점유 만료는 이전 실행의 중단을 증명하지 않으므로 별도 복구 전에는 다시 점유하지 않는다.
        if (job.status() == Status.PROCESSING) {
            throw conflict("Expired processing job requires recovery before a new claim");
        }
        if (job.status() != Status.QUEUED) {
            throw conflict("The job cannot be claimed");
        }
        // leaseId가 있는 요청은 기존 점유의 URL 갱신 전용이다. 재대기 중인 작업에
        // 이전 시도의 leaseId가 늦게 도착해도 새 실행을 시작해서는 안 된다.
        if (currentLease != null) throw conflict("The worker lease is no longer active");
        if (job.attemptCount() >= MAX_ATTEMPTS) throw conflict("The job has reached its attempt limit");
        if (job.availableAt() == null || job.availableAt().isAfter(now)) {
            throw conflict("The job is not yet available");
        }
        storage.revoke(job.resultGrantId());
        UUID lease = UUID.randomUUID();
        Instant leaseExpires = now.plus(LEASE_DURATION).truncatedTo(ChronoUnit.SECONDS);
        Claim response = inputGrant(job, lease, leaseExpires, now);
        repository.assignLease(id, lease, leaseExpires);
        return response;
    }

    /** 현재 유효한 점유만 갱신한다. 만료된 점유는 되살리지 않고, 만료 시각을 앞당기거나 시도 횟수를 늘리지 않는다. */
    @Transactional
    public LeaseHeartbeat heartbeat(UUID id, UUID lease) {
        MediaJob job = loadForUpdate(id);
        requireLease(job, lease);
        Instant now = Instant.now();
        if (!job.hasActiveLease(now)) throw conflict("The worker lease is no longer active");
        Instant requestedExpiry = now.plus(LEASE_DURATION).truncatedTo(ChronoUnit.SECONDS);
        Instant leaseExpires = job.leaseExpiresAt().isAfter(requestedExpiry) ? job.leaseExpiresAt() : requestedExpiry;
        if (repository.extendLease(id, lease, now, leaseExpires) != 1) {
            throw conflict("The worker lease is no longer active");
        }
        return new LeaseHeartbeat(leaseExpires);
    }

    @Transactional
    public JobView failInput(UUID id, UUID lease, Boolean retryable) {
        return failAttempt(id, lease, retryable, "INPUT_DOWNLOAD_FAILED");
    }

    @Transactional
    public JobView failProcessing(UUID id, UUID lease, Boolean retryable, String code, String matching, Integer registeredImages, Integer totalImages) {     
        if (code == null && matching == null && registeredImages == null && totalImages == null) {
            return failAttempt(id, lease, retryable, "PROCESSING_FAILED");
        }
        if (!"INSUFFICIENT_REGISTERED_IMAGES".equals(code)) {
            throw new ResponseStatusException(BAD_REQUEST, "Unknown processing failure code");
        }
        if (!"sequential".equals(matching) && !"exhaustive".equals(matching)) {
            throw new ResponseStatusException(BAD_REQUEST, "Invalid COLMAP matching mode");
        }
        if (registeredImages == null || totalImages == null || totalImages <= 0
                || registeredImages < 0 || registeredImages > totalImages) {
            throw new ResponseStatusException(BAD_REQUEST, "Invalid registered image counts");
        }
        if (!Boolean.FALSE.equals(retryable)) {
            throw new ResponseStatusException(BAD_REQUEST, "Registration failure requires user approval to retry");
        }

        return failAttempt(id, lease, false, code);
    }

    /**
     * 운영자가 이전 실행의 종료를 확인한 만료 점유만 복구한다. 백엔드는 확인 선언을
     * 검사하지만 GPU 프로세스 종료를 직접 검증하지 않는다. 기존 결과 쓰기 권한과
     * 결과 키 참조를 정리하며, 저장된 결과 객체 자체는 삭제하지 않는다.
     */
    @Transactional
    public JobView recoverExpired(UUID id, UUID lease, Boolean previousExecutionStopped, Boolean retryable) {
        if (!Boolean.TRUE.equals(previousExecutionStopped)) {
            throw new ResponseStatusException(BAD_REQUEST, "Confirm the previous execution has stopped");
        }
        if (retryable == null) throw new ResponseStatusException(BAD_REQUEST, "Specify whether the failure is retryable");
        MediaJob job = loadForUpdate(id);
        if (lease != null && lease.equals(job.lastFailureLeaseId())
                && "LEASE_EXPIRED".equals(job.lastErrorCode())
                && (job.status() == Status.QUEUED || job.status() == Status.FAILED)) return view(job);
        requireLease(job, lease);
        Instant now = Instant.now();
        if (job.status() != Status.PROCESSING || job.attemptCount() < 1 || job.leaseExpiresAt() == null
                || job.leaseExpiresAt().isAfter(now)) {
            throw conflict("The job does not have an expired processing lease");
        }
        boolean retry = retryable && job.attemptCount() < MAX_ATTEMPTS;
        Status next = retry ? Status.QUEUED : Status.FAILED;
        Instant available = retry ? retryAt(now, job.attemptCount()) : null;
        storage.revoke(job.resultGrantId());
        if (repository.recordExpiredRecovery(id, lease, now, next, available) != 1) {
            throw conflict("The expired lease changed during recovery");
        }
        return new JobView(id, next);
    }

    /**
     * 현재 점유의 결과 업로드 전 실패만 기록한다. 같은 점유·오류 종류의 재보고는
     * 기존 상태를 반환하고, 재시도는 남은 시도가 있을 때 available_at 이후로 미룬다.
     */
    private JobView failAttempt(UUID id, UUID lease, Boolean retryable, String errorCode) {
        if (retryable == null) throw new ResponseStatusException(BAD_REQUEST, "Specify whether the failure is retryable");
        MediaJob job = loadForUpdate(id);
        if (lease != null && lease.equals(job.lastFailureLeaseId()) &&
                errorCode.equals(job.lastErrorCode()) &&
                (job.status() == Status.QUEUED || job.status() == Status.FAILED)) return view(job);
        requireLease(job, lease);
        requireActiveLease(job);
        if (job.resultGrantId() != null || job.resultKey() != null) {
            throw conflict("Failure can only be reported before result upload");
        }
        boolean retry = retryable && job.attemptCount() < MAX_ATTEMPTS;
        Status next = retry ? Status.QUEUED : Status.FAILED;
        Instant available = retry ? retryAt(Instant.now(), job.attemptCount()) : null;
        repository.recordFailure(id, lease, next, available, errorCode);
        return new JobView(id, next);
    }

    private static Instant retryAt(Instant now, int attemptCount) {
        return now.plus(RETRY_DELAY.multipliedBy(1L << (attemptCount - 1)));
    }

    @Transactional
    public Transfer startResult(UUID id, UUID lease, Long bytes) {
        checkSize(bytes, MAX_PLY);
        MediaJob job = loadForUpdate(id);
        requireLease(job, lease);
        requireActiveLease(job);
        storage.revoke(job.resultGrantId());
        Instant writeDeadline = Instant.now().plus(WRITE_GRANT_DURATION).truncatedTo(ChronoUnit.SECONDS);
        Instant expires = writeDeadline.isBefore(job.leaseExpiresAt()) ? writeDeadline : job.leaseExpiresAt();
        // 재업로드는 새 객체 키를 사용해 이전 시도의 결과 파일과 섞이지 않게 한다.
        String key = "outputs/" + id + "/" + UUID.randomUUID() + "/room.ply";
        var grant = storage.grant(key, WRITE, expires);
        repository.recordResultUpload(id, grant.id(), key, bytes);
        return new Transfer(grant.url(), "PUT", expires, bytes);
    }

    @Transactional
    public JobView finishResult(UUID id, UUID lease) {
        MediaJob job = loadForUpdate(id);
        requireLease(job, lease);
        // 이미 완료된 동일 점유의 응답만 만료 후 재확인할 수 있다. 미완료 작업에는 유효한 점유가 필요하다.
        if (job.status() == Status.RESULT_READY) return view(job);
        requireActiveLease(job);
        if (job.resultGrantId() == null) throw conflict("Request a result upload grant first");
        storage.revoke(job.resultGrantId());
        verifySize(job.resultKey(), job.resultBytes());
        repository.markResultReady(id);
        return new JobView(id, Status.RESULT_READY);
    }

    private Claim inputGrant(MediaJob job, UUID lease, Instant leaseExpires, Instant now) {
        return new Claim(lease, leaseExpires, VIDEO_EXTENSIONS.get(job.videoType()),
                grant(inputKey(job.id(), job.videoType()), READ, now.plus(READ_GRANT_DURATION), job.videoBytes()));
    }

    private static String inputKey(UUID id, String videoType) {
        return "inputs/" + id + "/source" + VIDEO_EXTENSIONS.get(videoType);
    }

    private Transfer grant(String key, MediaStorage.Access access, Instant expires, long bytes) {
        return new Transfer(storage.grant(key, access, expires).url(), access == READ ? "GET" : "PUT", expires, bytes);
    }

    private void verifySize(String key, long expected) {
        // 임시 쓰기 링크 자체는 업로드 요청에 적힌 파일 크기를 강제하지 않는다.
        if (storage.size(key) != expected) throw conflict("Uploaded file size does not match");
    }

    private MediaJob load(UUID id) {
        return repository.findById(id).orElseThrow(MediaJobs::missing);
    }

    private MediaJob loadForUpdate(UUID id) {
        return repository.findByIdForUpdate(id).orElseThrow(MediaJobs::missing);
    }

    private static JobView view(MediaJob job) {
        return new JobView(job.id(), job.status());
    }

    private static MediaJob owned(MediaJob job, String owner) {
        if (!job.ownerId().equals(owner)) throw missing();
        return job;
    }

    private static void requireActiveLease(MediaJob job) {
        if (!job.hasActiveLease(Instant.now())) {
            throw conflict("The worker lease is no longer active");
        }
    }

    private static void requireLease(MediaJob job, UUID lease) {
        if (lease == null || !lease.equals(job.leaseId())) throw conflict("The worker does not hold this job");
    }

    private static void checkSize(Long bytes, long max) {
        if (bytes == null || bytes <= 0 || bytes > max) {
            throw new ResponseStatusException(BAD_REQUEST, "The file is empty or exceeds the size limit");
        }
    }

    private static ResponseStatusException missing() { return new ResponseStatusException(NOT_FOUND, "Job not found"); }
    private static ResponseStatusException conflict(String reason) { return new ResponseStatusException(CONFLICT, reason); }
}
