package io.bangpick.backend.media;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/** Runs only when the configured URL names a local bangpick_queue_test database. */
@EnabledIfEnvironmentVariable(named = "BANGPICK_QUEUE_TEST_MYSQL_URL",
        matches = "jdbc:mysql://(127\\.0\\.0\\.1|localhost):[0-9]+/bangpick_queue_test")
@SpringBootTest(classes = MediaTransferTests.TestApplication.class, properties = {
        "bangpick.media.enabled=true", "spring.flyway.enabled=true",
        "spring.datasource.url=${BANGPICK_QUEUE_TEST_MYSQL_URL}",
        "spring.datasource.username=${BANGPICK_QUEUE_TEST_MYSQL_USER}",
        "spring.datasource.password=${BANGPICK_QUEUE_TEST_MYSQL_PASSWORD}"
})
class MediaQueueMySqlConcurrencyTests {
    @Autowired JdbcTemplate jdbc;
    @Autowired DataSource dataSource;
    @Autowired MediaJobs jobs;
    private final List<UUID> created = new ArrayList<>();

    @AfterEach
    void removeTestJobs() {
        for (UUID id : created) {
            jdbc.update("DELETE FROM media_jobs WHERE id = ?", id.toString());
        }
    }

    @Test
    void simultaneousClaimsNeverAssignTheSameJobTwice() throws Exception {
        UUID id = queuedJob(100);
        var start = new CountDownLatch(1);
        try (var workers = Executors.newFixedThreadPool(2)) {
            var first = workers.submit(() -> {
                start.await();
                return jobs.claimNext();
            });
            var second = workers.submit(() -> {
                start.await();
                return jobs.claimNext();
            });
            start.countDown();
            List<Optional<MediaJobs.NextClaim>> claims = List.of(
                    first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS));
            assertThat(claims.stream().filter(Optional::isPresent).count()).isEqualTo(1);
            assertThat(claims.stream().flatMap(Optional::stream).map(MediaJobs.NextClaim::jobId).toList())
                    .containsExactly(id);
        }
        assertThat(jdbc.queryForObject("SELECT attempt_count FROM media_jobs WHERE id = ?", Integer.class,
                id.toString())).isEqualTo(1);
    }

    @Test
    void lockedOldestJobIsSkippedUntilItsTransactionEnds() throws Exception {
        UUID oldest = queuedJob(100);
        UUID next = queuedJob(200);
        try (Connection locked = dataSource.getConnection()) {
            locked.setAutoCommit(false);
            try {
                try (var statement = locked.prepareStatement("SELECT id FROM media_jobs WHERE id = ? FOR UPDATE")) {
                    statement.setString(1, oldest.toString());
                    try (var rows = statement.executeQuery()) {
                        assertThat(rows.next()).isTrue();
                    }
                }
                try (var worker = Executors.newSingleThreadExecutor()) {
                    var claim = worker.submit(jobs::claimNext).get(10, TimeUnit.SECONDS);
                    assertThat(claim).isPresent();
                    assertThat(claim.orElseThrow().jobId()).isEqualTo(next);
                }
            } finally {
                locked.rollback();
            }
        }
        assertThat(jobs.claimNext().orElseThrow().jobId()).isEqualTo(oldest);
        assertThat(jdbc.queryForObject("SELECT attempt_count FROM media_jobs WHERE id = ?", Integer.class,
                oldest.toString())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT attempt_count FROM media_jobs WHERE id = ?", Integer.class,
                next.toString())).isEqualTo(1);
    }

    private UUID queuedJob(long queuedAt) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO media_jobs (id, owner_id, status, video_type, video_bytes,
                                        upload_expires, queued_at, available_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """, id.toString(), "mysql-queue-test", "QUEUED", "video/mp4", 1L, 0L, queuedAt, 0L);
        created.add(id);
        return id;
    }
}
