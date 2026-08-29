package site.kael.cpa.console.core.database;

import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Applies embedded, forward-only SQLite migrations in version order. */
public final class DatabaseMigrationRunner {
    private static final String MIGRATION_TABLE = """
            CREATE TABLE IF NOT EXISTS schema_migrations (
                version TEXT PRIMARY KEY,
                description TEXT NOT NULL,
                checksum TEXT NOT NULL,
                installed_at TIMESTAMP NOT NULL,
                execution_time_ms INTEGER NOT NULL DEFAULT 0
            )
            """;
    private static final String MIGRATION_PATTERN = "classpath*:db/migration/*.sql";
    private static final Pattern FILE_PATTERN = Pattern.compile("^(\\d+)__(.+)\\.sql$");

    private final DataSource dataSource;
    private final JdbcTemplate jdbcTemplate;

    public DatabaseMigrationRunner(DataSource dataSource) {
        this.dataSource = dataSource;
        this.jdbcTemplate = new JdbcTemplate(dataSource);
    }

    public void migrate() {
        try {
            jdbcTemplate.execute(MIGRATION_TABLE);
            List<Migration> migrations = loadMigrations();
            if (migrations.isEmpty()) throw new IllegalStateException("No database migrations found");
            establishLegacyBaselineIfNeeded(migrations.getFirst());
            for (Migration migration : migrations) applyIfNeeded(migration);
        } catch (Exception exception) {
            if (exception instanceof IllegalStateException illegalStateException) throw illegalStateException;
            throw new IllegalStateException("Database migration failed", exception);
        }
    }

    private List<Migration> loadMigrations() throws IOException {
        Resource[] resources = new PathMatchingResourcePatternResolver().getResources(MIGRATION_PATTERN);
        List<Migration> migrations = Arrays.stream(resources)
                .map(resource -> toMigration(resource))
                .sorted(Comparator.comparing(Migration::version))
                .toList();
        for (int index = 1; index < migrations.size(); index++) {
            if (migrations.get(index - 1).version().equals(migrations.get(index).version())) {
                throw new IllegalStateException("Duplicate database migration version: " + migrations.get(index).version());
            }
        }
        return migrations;
    }

    private Migration toMigration(Resource resource) {
        try {
            String filename = resource.getFilename();
            if (filename == null) throw new IllegalStateException("Migration filename is missing");
            Matcher matcher = FILE_PATTERN.matcher(filename);
            if (!matcher.matches()) throw new IllegalStateException("Invalid migration filename: " + filename);
            String sql = resource.getContentAsString(StandardCharsets.UTF_8);
            String version = matcher.group(1);
            String description = matcher.group(2).replace('_', ' ');
            return new Migration(filename, version, description, sql, checksum(sql));
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to read migration " + resource, exception);
        }
    }

    private void applyIfNeeded(Migration migration) throws Exception {
        String existing = findChecksum(migration.version());
        if (existing != null) {
            if (!existing.equals(migration.checksum())) {
                throw new IllegalStateException("Migration " + migration.filename() + " checksum mismatch; create a new migration instead of modifying an applied migration");
            }
            return;
        }
        Instant started = Instant.now();
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                executeSql(connection, migration.sql());
                long elapsed = Duration.between(started, Instant.now()).toMillis();
                try (var statement = connection.prepareStatement("INSERT INTO schema_migrations (version, description, checksum, installed_at, execution_time_ms) VALUES (?, ?, ?, ?, ?)")) {
                    statement.setString(1, migration.version());
                    statement.setString(2, migration.description());
                    statement.setString(3, migration.checksum());
                    statement.setTimestamp(4, java.sql.Timestamp.from(Instant.now()));
                    statement.setLong(5, elapsed);
                    statement.executeUpdate();
                }
                connection.commit();
            } catch (Exception exception) {
                connection.rollback();
                throw exception;
            } finally {
                connection.setAutoCommit(true);
            }
        }
    }

    private String findChecksum(String version) {
        List<String> values = jdbcTemplate.query("SELECT checksum FROM schema_migrations WHERE version = ?", (rs, row) -> rs.getString(1), version);
        return values.isEmpty() ? null : values.getFirst();
    }

    private void establishLegacyBaselineIfNeeded(Migration baseline) {
        if (findChecksum(baseline.version()) != null) return;
        Boolean hasUsers = jdbcTemplate.queryForObject("SELECT EXISTS (SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = 'users')", Boolean.class);
        if (!Boolean.TRUE.equals(hasUsers)) return;
        List<String> requiredTables = List.of("users", "credentials", "usage_event_inbox", "usage_events");
        List<String> missing = requiredTables.stream().filter(table -> !tableExists(table)).toList();
        if (!missing.isEmpty()) {
            throw new IllegalStateException("Existing database is only partially initialized; missing tables: " + String.join(", ", missing));
        }
        List<String> requiredColumns = List.of(
                "users.api_key_hash", "users.api_key_ciphertext",
                "credentials.reference_id", "usage_event_inbox.raw_event_json",
                "usage_events.request_id", "usage_events.api_key_hash", "usage_events.total_tokens");
        List<String> missingColumns = requiredColumns.stream()
                .filter(column -> !columnExists(column.substring(0, column.indexOf('.')), column.substring(column.indexOf('.') + 1)))
                .toList();
        if (!missingColumns.isEmpty()) {
            throw new IllegalStateException("Existing database is only partially initialized; missing columns: " + String.join(", ", missingColumns));
        }
        jdbcTemplate.update("INSERT INTO schema_migrations (version, description, checksum, installed_at, execution_time_ms) VALUES (?, ?, ?, ?, ?)",
                baseline.version(), baseline.description(), baseline.checksum(), java.sql.Timestamp.from(Instant.now()), 0);
    }

    private boolean tableExists(String table) {
        Boolean exists = jdbcTemplate.queryForObject("SELECT EXISTS (SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = ?)", Boolean.class, table);
        return Boolean.TRUE.equals(exists);
    }

    private boolean columnExists(String table, String column) {
        List<String> columns = jdbcTemplate.query("PRAGMA table_info(" + quoteIdentifier(table) + ")", (rs, row) -> rs.getString("name"));
        return columns.stream().anyMatch(column::equals);
    }

    private static String quoteIdentifier(String value) {
        return "\"" + value.replace("\"", "\"\"") + "\"";
    }

    private static void executeSql(Connection connection, String sql) throws Exception {
        for (String statementSql : splitStatements(sql)) {
            try (Statement statement = connection.createStatement()) {
                statement.execute(statementSql);
            }
        }
    }

    static List<String> splitStatements(String sql) {
        List<String> statements = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean singleQuote = false;
        boolean doubleQuote = false;
        boolean lineComment = false;
        for (int i = 0; i < sql.length(); i++) {
            char character = sql.charAt(i);
            char next = i + 1 < sql.length() ? sql.charAt(i + 1) : 0;
            if (lineComment) {
                current.append(character);
                if (character == '\n') lineComment = false;
                continue;
            }
            if (!singleQuote && !doubleQuote && character == '-' && next == '-') {
                lineComment = true;
                current.append(character).append(next);
                i++;
                continue;
            }
            if (character == '\'' && !doubleQuote) singleQuote = !singleQuote;
            else if (character == '"' && !singleQuote) doubleQuote = !doubleQuote;
            if (character == ';' && !singleQuote && !doubleQuote) {
                if (!current.toString().trim().isEmpty()) statements.add(current.toString().trim());
                current.setLength(0);
            } else current.append(character);
        }
        if (!current.toString().trim().isEmpty()) statements.add(current.toString().trim());
        return statements;
    }

    private static String checksum(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.trim().getBytes(StandardCharsets.UTF_8))); }
        catch (Exception exception) { throw new IllegalStateException("SHA-256 unavailable", exception); }
    }

    private record Migration(String filename, String version, String description, String sql, String checksum) {}
}
