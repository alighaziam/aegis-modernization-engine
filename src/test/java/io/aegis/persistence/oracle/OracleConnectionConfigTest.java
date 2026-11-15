package io.aegis.persistence.oracle;

import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class OracleConnectionConfigTest {
    @Test void loadsOracleSettingsFromEnvironmentMap() {
        var config = OracleConnectionConfig.fromEnvironment(Map.of(
                "AEGIS_ORACLE_JDBC_URL", "jdbc:oracle:thin:@//localhost:1521/FREEPDB1",
                "AEGIS_ORACLE_USER", "AEGIS",
                "AEGIS_ORACLE_PASSWORD", "secret",
                "AEGIS_ORACLE_POOL_SIZE", "8",
                "AEGIS_ORACLE_CONNECTION_TIMEOUT_MS", "15000"));
        assertEquals("jdbc:oracle:thin:@//localhost:1521/FREEPDB1", config.jdbcUrl());
        assertEquals(8, config.maximumPoolSize());
        assertEquals(15000, config.connectionTimeoutMillis());
    }

    @Test void requiresOracleUrlAndCredentials() {
        assertThrows(IllegalArgumentException.class, () -> OracleConnectionConfig.fromEnvironment(Map.of()));
        assertThrows(IllegalArgumentException.class, () -> new OracleConnectionConfig(
                "jdbc:postgresql://localhost/db", "aegis", "secret", 5, 10000));
    }

    @Test void rejectsUnsafePoolSettings() {
        assertThrows(IllegalArgumentException.class, () -> new OracleConnectionConfig(
                "jdbc:oracle:thin:@localhost:1521/FREEPDB1", "aegis", "secret", 0, 10000));
    }
}
