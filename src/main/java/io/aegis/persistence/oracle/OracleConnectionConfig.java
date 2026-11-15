package io.aegis.persistence.oracle;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.util.Map;

/** Oracle connection settings are supplied only through environment variables. */
public record OracleConnectionConfig(String jdbcUrl, String username, String password,
                                     int maximumPoolSize, long connectionTimeoutMillis) {
    public OracleConnectionConfig {
        if (jdbcUrl == null || !jdbcUrl.startsWith("jdbc:oracle:thin:@"))
            throw new IllegalArgumentException("AEGIS_ORACLE_JDBC_URL must be a jdbc:oracle:thin:@ URL");
        if (username == null || username.isBlank()) throw new IllegalArgumentException("AEGIS_ORACLE_USER is required");
        if (password == null || password.isBlank()) throw new IllegalArgumentException("AEGIS_ORACLE_PASSWORD is required");
        if (maximumPoolSize < 1 || maximumPoolSize > 50) throw new IllegalArgumentException("Pool size must be between 1 and 50");
        if (connectionTimeoutMillis < 1000 || connectionTimeoutMillis > 120000)
            throw new IllegalArgumentException("Connection timeout must be between 1000 and 120000 ms");
    }
    public static OracleConnectionConfig fromEnvironment() { return fromEnvironment(System.getenv()); }
    static OracleConnectionConfig fromEnvironment(Map<String, String> env) {
        return new OracleConnectionConfig(env.get("AEGIS_ORACLE_JDBC_URL"), env.get("AEGIS_ORACLE_USER"),
                env.get("AEGIS_ORACLE_PASSWORD"),
                parseInt(env.getOrDefault("AEGIS_ORACLE_POOL_SIZE", "5"), "AEGIS_ORACLE_POOL_SIZE"),
                parseLong(env.getOrDefault("AEGIS_ORACLE_CONNECTION_TIMEOUT_MS", "10000"),
                        "AEGIS_ORACLE_CONNECTION_TIMEOUT_MS"));
    }
    public HikariDataSource createDataSource() {
        HikariConfig config = new HikariConfig();
        config.setPoolName("aegis-oracle-pool");
        config.setJdbcUrl(jdbcUrl);
        config.setUsername(username);
        config.setPassword(password);
        config.setMaximumPoolSize(maximumPoolSize);
        config.setMinimumIdle(1);
        config.setConnectionTimeout(connectionTimeoutMillis);
        config.setInitializationFailTimeout(-1);
        config.setAutoCommit(true);
        return new HikariDataSource(config);
    }
    private static int parseInt(String value, String name) {
        try { return Integer.parseInt(value); }
        catch (RuntimeException e) { throw new IllegalArgumentException(name + " must be an integer"); }
    }
    private static long parseLong(String value, String name) {
        try { return Long.parseLong(value); }
        catch (RuntimeException e) { throw new IllegalArgumentException(name + " must be an integer"); }
    }
}
