package site.kael.cpa.console.core.monitor.dao;

import org.springframework.jdbc.datasource.DataSourceUtils;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Identity-free observation-gap metadata DAO (D4.3 / D6.2). One half-open millisecond
 * interval per scheduler downtime, plus the singleton recovery marker that lets the next
 * boot measure the gap even when no run was active during it. T07 writes; T09 reads.
 */
public final class ObservationGapDao {

    private final DataSource dataSource;

    public ObservationGapDao(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    }

    /** Reads the previous durable marker; initializes it to {@code nowMs} on first boot. */
    public long readMarker(long nowMs) throws SQLException {
        String insert = "INSERT INTO monitor_recovery_marker (id, marker_value) SELECT 1, ? "
                + "WHERE NOT EXISTS (SELECT 1 FROM monitor_recovery_marker)";
        String select = "SELECT marker_value FROM monitor_recovery_marker WHERE id = 1";
        try (Connection connection = DataSourceUtils.getConnection(dataSource);
             PreparedStatement insertStatement = connection.prepareStatement(insert)) {
            insertStatement.setLong(1, nowMs);
            insertStatement.executeUpdate();
        }
        try (Connection connection = DataSourceUtils.getConnection(dataSource);
             PreparedStatement statement = connection.prepareStatement(select);
             ResultSet resultSet = statement.executeQuery()) {
            if (!resultSet.next()) {
                throw new SQLException("Recovery marker row disappeared during read");
            }
            return resultSet.getLong(1);
        }
    }

    /** Advances the durable marker to {@code nowMs}. */
    public void writeMarker(long nowMs) throws SQLException {
        String sql = "INSERT INTO monitor_recovery_marker (id, marker_value) VALUES (1, ?) "
                + "ON CONFLICT (id) DO UPDATE SET marker_value = excluded.marker_value";
        try (Connection connection = DataSourceUtils.getConnection(dataSource);
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, nowMs);
            statement.executeUpdate();
        }
    }

    /** Records one downtime interval; callers own the transaction and ordering. */
    public void insertGap(long startMs, long endMs) throws SQLException {
        if (endMs <= startMs) {
            return;
        }
        String sql = "INSERT INTO monitor_observation_gaps (gap_start, gap_end) VALUES (?, ?)";
        try (Connection connection = DataSourceUtils.getConnection(dataSource);
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, startMs);
            statement.setLong(2, endMs);
            statement.executeUpdate();
        }
    }

    /** Gaps overlapping the half-open window [{@code startMs}, {@code endMs}), sorted. */
    public List<Gap> findGapsOverlapping(long startMs, long endMs) throws SQLException {
        String sql = "SELECT gap_start, gap_end FROM monitor_observation_gaps "
                + "WHERE gap_end > ? AND gap_start < ? ORDER BY gap_start, gap_end";
        List<Gap> gaps = new ArrayList<>();
        try (Connection connection = DataSourceUtils.getConnection(dataSource);
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, startMs);
            statement.setLong(2, endMs);
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    gaps.add(new Gap(resultSet.getLong(1), resultSet.getLong(2)));
                }
            }
        }
        return List.copyOf(gaps);
    }

    /** Identity-free half-open interval. */
    public record Gap(long startMs, long endMs) {
    }
}
