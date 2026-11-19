package me.lucko.bytebin.service;

import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DatabaseReadinessTest {

    /** Simulated database state: true = connections succeed and validate. */
    private boolean up = true;
    private boolean connectionValid = true;
    private final AtomicLong now = new AtomicLong();

    private final DatabaseReadiness readiness =
            new DatabaseReadiness(dataSource(), Duration.ofSeconds(15), now::get);

    @Test
    void notReadyBeforeFirstCheck() {
        assertFalse(readiness.isReady());
    }

    @Test
    void followsDatabaseThroughOutageAndRecovery() {
        readiness.check();
        assertTrue(readiness.isReady());

        up = false;
        readiness.check();
        assertFalse(readiness.isReady(), "a failed check is unready immediately, without waiting for maxAge");

        up = true;
        readiness.check();
        assertTrue(readiness.isReady());
    }

    @Test
    void invalidConnectionIsNotReady() {
        connectionValid = false;
        readiness.check();
        assertFalse(readiness.isReady());
    }

    @Test
    void staleSuccessIsNotReady() {
        // e.g. the next check is stuck waiting on the pool's connection timeout
        readiness.check();
        now.addAndGet(Duration.ofSeconds(15).toNanos());
        assertTrue(readiness.isReady());
        now.addAndGet(1);
        assertFalse(readiness.isReady());
    }

    private DataSource dataSource() {
        Connection connection = (Connection) Proxy.newProxyInstance(
                Connection.class.getClassLoader(), new Class<?>[]{Connection.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "isValid" -> connectionValid;
                    case "close" -> null;
                    default -> throw new UnsupportedOperationException(method.getName());
                });
        return (DataSource) Proxy.newProxyInstance(
                DataSource.class.getClassLoader(), new Class<?>[]{DataSource.class},
                (proxy, method, args) -> {
                    if (!method.getName().equals("getConnection")) {
                        throw new UnsupportedOperationException(method.getName());
                    }
                    if (!up) {
                        throw new SQLException("Connection refused");
                    }
                    return connection;
                });
    }
}
