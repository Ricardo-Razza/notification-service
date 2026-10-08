package dev.ricardo.notification.infrastructure.persistence;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import java.sql.DriverManager;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

@Testcontainers
class TemplateScopeMigrationTest {
    @Container static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.4");
    @Test void upgradesExistingRowsAndEnforcesGlobalUniqueness() throws Exception {
        Flyway.configure().dataSource(mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword()).target("5").load().migrate();
        String id = UUID.randomUUID().toString();
        try (var c = DriverManager.getConnection(mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword())) {
            try (var stmt = c.prepareStatement("INSERT INTO notification_template (id,code,channel,subject,content,created_at,updated_at) VALUES (?, 'EXISTING', 'EMAIL', 'old subject', 'old body', UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))")) {
                stmt.setString(1, id); stmt.executeUpdate();
            }
            Flyway.configure().dataSource(mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword()).target("6").load().migrate();
            try (var s = c.createStatement(); var rows = s.executeQuery("SELECT id,content,application_id,scope_key FROM notification_template WHERE code='EXISTING'")) {
                assertThat(rows.next()).isTrue(); assertThat(rows.getString("id")).isEqualTo(id);
                assertThat(rows.getString("content")).isEqualTo("old body");
                assertThat(rows.getString("application_id")).isNull(); assertThat(rows.getString("scope_key")).isEqualTo("GLOBAL");
            }
            try (var s = c.createStatement()) {
                assertThatThrownBy(() -> s.executeUpdate("INSERT INTO notification_template (id,code,channel,subject,content,created_at,updated_at) VALUES (UUID(), 'EXISTING', 'EMAIL', 'new', 'new', UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))"))
                        .isInstanceOf(java.sql.SQLException.class);
            }
        }
    }
}
