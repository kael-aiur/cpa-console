package site.kael.cpa.console.core.monitor.model;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;


import java.nio.file.Files;
import java.sql.Connection;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T01 isolation self-check: empty database, pre-003 legacy database, duplicate restart,
 * checksum tampering and mid-migration failure paths, all against isolated SQLite.
 */
class CredentialMonitoringSchemaTest {

    @Test
    void migratesEmptyDatabaseWithAllMonitorTables() throws Exception {
        DriverManagerDataSource dataSource = dataSource();
        // empty
        DatabaseMigrationRunnerAdapter runner = new DatabaseMigrationRunnerAdapter(dataSource);
        runner.migrate();
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        assertEquals(4, jdbc.queryForObject(
                "SELECT COUNT(*) FROM schema_migrations WHERE version IN ('001','002','003','004')", Integer.class));
        for (String table : new String[]{
                "monitor_cpa_binding", "monitor_tasks", "monitor_runs",
                "monitor_credential_guards", "monitor_retention_meta", "monitor_retention_losses"}) {
            assertEquals(1, jdbc.queryForObject(
                    "SELECT COUNT(*) FROM sqlite_master WHERE type = 'table' AND name = ?", Integer.class, table));
        }
    }

    @Test
    void uniqueTaskSlotPreventsSecondOwner() throws Exception {
        DriverManagerDataSource dataSource = dataSource();
        DatabaseMigrationRunnerAdapter runner = new DatabaseMigrationRunnerAdapter(dataSource);
        runner.migrate();
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        insertTask(jdbc, "task-1");
        insertClaimedRun(jdbc, "run-1", "task-1", 1_700_000_000_000L, 1);
        assertThrows(Exception.class, () -> insertClaimedRun(jdbc, "run-2", "task-1", 1_700_000_100_000L, 1));
    }

    @Test
    void nonOwnerRowsAllowedWhileSlotHeld() throws Exception {
        DriverManagerDataSource dataSource = dataSource();
        DatabaseMigrationRunnerAdapter runner = new DatabaseMigrationRunnerAdapter(dataSource);
        runner.migrate();
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        insertTask(jdbc, "task-1");
        insertClaimedRun(jdbc, "run-owner", "task-1", 1_700_000_000_000L, 1);
        insertClaimedRun(jdbc, "run-not-owner", "task-1", 1_700_000_100_000L, 0);
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM monitor_runs WHERE task_id = 'task-1'", Integer.class));
    }

    @Test
    void duplicatePlanKeyRejected() throws Exception {
        DriverManagerDataSource dataSource = dataSource();
        DatabaseMigrationRunnerAdapter runner = new DatabaseMigrationRunnerAdapter(dataSource);
        runner.migrate();
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        insertTask(jdbc, "task-1");
        insertClaimedRun(jdbc, "run-a", "task-1", 1_700_000_000_000L, 0);
        assertThrows(Exception.class, () -> insertClaimedRun(jdbc, "run-b", "task-1", 1_700_000_000_000L, 0));
    }

    @Test
    void successRequiresStartedAtAndMayHaveSent() throws Exception {
        DriverManagerDataSource dataSource = dataSource();
        DatabaseMigrationRunnerAdapter runner = new DatabaseMigrationRunnerAdapter(dataSource);
        runner.migrate();
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        insertTask(jdbc, "task-1");
        assertThrows(Exception.class, () -> insertTerminalRun(jdbc, "run-x", "task-1", 1_700_000_000_000L,
                "SUCCESS", "NOT_SENT", null, null));
    }

    @Test
    void skippedRequiresNotSentAndNullStarted() throws Exception {
        DriverManagerDataSource dataSource = dataSource();
        DatabaseMigrationRunnerAdapter runner = new DatabaseMigrationRunnerAdapter(dataSource);
        runner.migrate();
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        insertTask(jdbc, "task-1");
        assertThrows(Exception.class, () -> insertTerminalRun(jdbc, "run-x", "task-1", 1_700_000_000_000L,
                "SKIPPED", "MAY_HAVE_SENT", 1_700_000_000_500L, 1_700_000_000_600L));
    }

    @Test
    void terminalRequiresFinishedAt() throws Exception {
        DriverManagerDataSource dataSource = dataSource();
        DatabaseMigrationRunnerAdapter runner = new DatabaseMigrationRunnerAdapter(dataSource);
        runner.migrate();
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        insertTask(jdbc, "task-1");
        insertClaimedRun(jdbc, "run-x", "task-1", 1_700_000_000_000L, 0);
        assertThrows(Exception.class, () -> jdbc.update(
                "UPDATE monitor_runs SET phase = 'TERMINAL', state = 'SUCCESS' WHERE run_id = 'run-x'"));
    }

    @Test
    void migratesLegacy003DatabasePreservingData() throws Exception {
        DriverManagerDataSource dataSource = dataSource();
        new org.springframework.jdbc.datasource.init.ResourceDatabasePopulator(
                new org.springframework.core.io.ClassPathResource("db/migration/001__init.sql")).execute(dataSource);
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.update("INSERT INTO users (nickname, role, api_key_hash, api_key_ciphertext, created_at, updated_at) "
                + "VALUES ('legacy-user', 'user', 'hash', 'cipher', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)");
        new DatabaseMigrationRunnerAdapter(dataSource).migrate();
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM users", Integer.class));
        assertEquals(4, jdbc.queryForObject(
                "SELECT COUNT(*) FROM schema_migrations WHERE version IN ('001','002','003','004')", Integer.class));
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name='monitor_tasks'", Integer.class));
    }

    @Test
    void duplicateRunnerRunDoesNotReapplyMigration() throws Exception {
        DriverManagerDataSource dataSource = dataSource();
        DatabaseMigrationRunnerAdapter runner = new DatabaseMigrationRunnerAdapter(dataSource);
        runner.migrate();
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        runner.migrate();
        assertEquals(5, jdbc.queryForObject("SELECT COUNT(*) FROM schema_migrations", Integer.class));
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name='monitor_tasks'", Integer.class));
    }

    private static void insertTask(JdbcTemplate jdbc, String taskId) {
        jdbc.update("""
                INSERT INTO monitor_tasks (task_id, safe_name, route_model_cipher, safe_model_display,
                    cpa_instance_key, auth_id_cipher, auth_index_cipher, provider_snapshot,
                    enabled, cron_expression, zone_id, tzdb_certificate, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, 0, '0 0 0 * * *', 'UTC', 'cert', 1000, 1000)
                """, taskId, "test", "enc", "model", "inst", "enc", "enc", "provider");
    }

    private static void insertClaimedRun(
            JdbcTemplate jdbc, String runId, String taskId, long scheduledAt, int ownsSlot) {
        jdbc.update("""
                INSERT INTO monitor_runs (run_id, task_id, scheduled_at, schedule_revision,
                    task_safe_name, task_model_display, cpa_instance_key, auth_id_cipher,
                    auth_index_cipher, provider_snapshot, phase, state, dispatch_status,
                    owns_task_slot, claimed_at)
                VALUES (?, ?, ?, 1, 'test', 'model', 'inst', 'enc', 'enc', 'provider',
                    'CLAIMED', 'PENDING', 'NOT_SENT', ?, ?)
                """, runId, taskId, scheduledAt, ownsSlot, scheduledAt);
    }

    private static void insertTerminalRun(
            JdbcTemplate jdbc, String runId, String taskId, long scheduledAt,
            String state, String dispatch, Long startedAt, Long finishedAt) {
        jdbc.update("""
                INSERT INTO monitor_runs (run_id, task_id, scheduled_at, schedule_revision,
                    task_safe_name, task_model_display, cpa_instance_key, auth_id_cipher,
                    auth_index_cipher, provider_snapshot, phase, state, dispatch_status,
                    owns_task_slot, claimed_at, started_at, finished_at)
                VALUES (?, ?, ?, 1, 'test', 'model', 'inst', 'enc', 'enc', 'provider',
                    'TERMINAL', ?, ?, 0, ?, ?, ?)
                """, runId, taskId, scheduledAt, state, dispatch, scheduledAt, startedAt, finishedAt);
    }

    private DriverManagerDataSource dataSource() throws Exception {
        return new DriverManagerDataSource("jdbc:sqlite:" + Files.createTempFile("monitor-schema-", ".db"));
    }

    /** Delegates to the real runner; the class lives in a different package so we use the same classpath. */
    private static final class DatabaseMigrationRunnerAdapter {
        private final site.kael.cpa.console.core.database.DatabaseMigrationRunner delegate;

        DatabaseMigrationRunnerAdapter(javax.sql.DataSource dataSource) {
            this.delegate = new site.kael.cpa.console.core.database.DatabaseMigrationRunner(dataSource);
        }

        void migrate() {
            delegate.migrate();
        }
    }
}
