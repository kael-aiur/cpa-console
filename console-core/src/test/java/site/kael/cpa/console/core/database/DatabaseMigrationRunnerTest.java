package site.kael.cpa.console.core.database;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import java.nio.file.Files;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DatabaseMigrationRunnerTest {
    @Test
    void migratesNewDatabaseAndSkipsAlreadyAppliedMigrations() throws Exception {
        DriverManagerDataSource dataSource = dataSource();
        DatabaseMigrationRunner runner = new DatabaseMigrationRunner(dataSource);

        runner.migrate();
        runner.migrate();

        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        assertEquals(3, jdbc.queryForObject("SELECT COUNT(*) FROM schema_migrations", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM sqlite_master WHERE type = 'table' AND name = 'usage_events'", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM sqlite_master WHERE type = 'table' AND name = 'persistent_login_tokens'", Integer.class));
    }

    @Test
    void baselinesLegacyDatabaseWithoutRecreatingOrLosingData() throws Exception {
        DriverManagerDataSource dataSource = dataSource();
        new ResourceDatabasePopulator(new org.springframework.core.io.ClassPathResource("db/migration/001__init.sql")).execute(dataSource);
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.update("INSERT INTO users (nickname, role, api_key_hash, api_key_ciphertext, created_at, updated_at) VALUES (?, ?, ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)", "legacy", "user", "hash", "cipher");

        new DatabaseMigrationRunner(dataSource).migrate();

        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM users", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM schema_migrations WHERE version = '001'", Integer.class));
    }

    @Test
    void splitsSemicolonsInsideQuotedValues() {
        assertEquals(2, DatabaseMigrationRunner.splitStatements("INSERT INTO t VALUES ('a;b'); SELECT 1;").size());
    }

    private DriverManagerDataSource dataSource() throws Exception {
        return new DriverManagerDataSource("jdbc:sqlite:" + Files.createTempFile("migration-test-", ".db"));
    }
}
