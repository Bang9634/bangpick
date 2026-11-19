package io.bangpick.backend.auth;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
class MemberRepository {
    enum Role { USER, ADMIN }
    record Member(UUID id, Role role) {}

    private final JdbcTemplate jdbc;

    MemberRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional
    public Member findOrCreate(String provider, String subject) {
        byte[] subjectKey = subject.getBytes(UTF_8);
        // Concurrent first logins converge on the same member and preserve any assigned role.
        jdbc.update("""
                INSERT INTO members (id, identity_provider, provider_subject)
                VALUES (?, ?, ?)
                ON DUPLICATE KEY UPDATE id = id
                """, UUID.randomUUID().toString(), provider, subjectKey);
        return jdbc.queryForObject("""
                SELECT id, role FROM members WHERE identity_provider = ? AND provider_subject = ?
                """, (row, i) -> member(row), provider, subjectKey);
    }

    Optional<Member> findById(UUID id) {
        return jdbc.query("SELECT id, role FROM members WHERE id = ?",
                (row, i) -> member(row), id.toString()).stream().findFirst();
    }

    private static Member member(ResultSet row) throws SQLException {
        return new Member(UUID.fromString(row.getString("id")), Role.valueOf(row.getString("role")));
    }
}
