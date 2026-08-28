package site.kael.cpa.console.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

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
        new ResourceDatabasePopulator(new ClassPathResource("schema.sql")).execute(dataSource);
        return dataSource;
    }

    @Bean
    public JdbcTemplate jdbcTemplate(DataSource dataSource) {
        return new JdbcTemplate(dataSource);
    }
}
