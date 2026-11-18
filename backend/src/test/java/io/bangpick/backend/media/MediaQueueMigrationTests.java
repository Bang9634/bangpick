package io.bangpick.backend.media;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class MediaQueueMigrationTests {
    @Test
    void existingQueuedRowsBecomeAvailableWithoutInventingATime() {
        String url = "jdbc:h2:mem:queue_" + UUID.randomUUID().toString().replace("-", "")
                + ";MODE=MySQL;DB_CLOSE_DELAY=-1";
        Flyway.configure().dataSource(url, "sa", "").target("2").load().migrate();
        JdbcTemplate jdbc = new JdbcTemplate(new DriverManagerDataSource(url, "sa", ""));
        String legacy = insert(jdbc);
        String timed = insert(jdbc);
        jdbc.update("UPDATE media_jobs SET queued_at = ?, available_at = ? WHERE id = ?", 123L, 456L, timed);

        Flyway.configure().dataSource(url, "sa", "").load().migrate();

        assertThat(jdbc.queryForObject("SELECT queued_at FROM media_jobs WHERE id = ?", Long.class, legacy)).isZero();
        assertThat(jdbc.queryForObject("SELECT available_at FROM media_jobs WHERE id = ?", Long.class, legacy)).isZero();
        assertThat(jdbc.queryForObject("SELECT queued_at FROM media_jobs WHERE id = ?", Long.class, timed))
                .isEqualTo(123L);
        assertThat(jdbc.queryForObject("SELECT available_at FROM media_jobs WHERE id = ?", Long.class, timed))
                .isEqualTo(456L);
    }

    private static String insert(JdbcTemplate jdbc) {
        String id = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO media_jobs (id, owner_id, status, video_type, video_bytes, upload_expires)
                VALUES (?, 'owner', 'QUEUED', 'video/mp4', 1, 1)
                """, id);
        return id;
    }
}
