package site.kael.cpa.console.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import site.kael.cpa.console.core.database.DatabaseMigrationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.sqlite.SQLiteConfig;

import javax.sql.DataSource;
import java.nio.file.Files;
import java.nio.file.Path;

@Configuration
public class DatabaseConfig {
    @Bean
    public DataSource dataSource(
            @Value("${cpa.console.data-dir:${CPA_CONSOLE_DATA_DIR:.data}}") String dataDirectory
    ) throws Exception {
        Path directory = Path.of(dataDirectory).toAbsolutePath().normalize();
        Files.createDirectories(directory);
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName("org.sqlite.JDBC");
        dataSource.setUrl("jdbc:sqlite:" + directory.resolve("cpa-console.db"));
        SQLiteConfig sqliteConfig = new SQLiteConfig();
        // The application has independent read/write tasks sharing one SQLite file.
        // Wait briefly for transient locks and let readers proceed during writes.
        sqliteConfig.setBusyTimeout(10_000);
        sqliteConfig.setJournalMode(SQLiteConfig.JournalMode.WAL);
        sqliteConfig.setSynchronous(SQLiteConfig.SynchronousMode.NORMAL);
        dataSource.setConnectionProperties(sqliteConfig.toProperties());
        new DatabaseMigrationRunner(dataSource).migrate();
        return dataSource;
    }

    @Bean
    public JdbcTemplate jdbcTemplate(DataSource dataSource) {
        return new JdbcTemplate(dataSource);
    }
}
