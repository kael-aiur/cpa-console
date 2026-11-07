package site.kael.cpa.console.core.monitor.model;

import javax.sql.DataSource;

/** Test-only adapter so monitor manager tests can run the real migration runner. */
public final class CredentialMonitoringSchemaTestSupport {

    public void migrate(DataSource dataSource) {
        new site.kael.cpa.console.core.database.DatabaseMigrationRunner(dataSource).migrate();
    }
}
