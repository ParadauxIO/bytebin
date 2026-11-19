package me.lucko.bytebin;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import me.lucko.bytebin.util.Configuration;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Property;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Rehearses an upgrade and a database outage against a real PostgreSQL server
 * (version from the {@code postgres-it} Maven profile).
 *
 * <ol>
 *   <li>Seed a database at schema version 1, as an old deployment left it.</li>
 *   <li>Cold-start bytebin: Flyway must migrate to the latest version on this
 *       PostgreSQL version without warning that the version is untested.</li>
 *   <li>Create, read and update a paste.</li>
 *   <li>Stop PostgreSQL: readiness must fail while liveness stays up.</li>
 *   <li>Restart PostgreSQL on the same data directory: readiness and writes
 *       recover, and usage events buffered during the outage are written.</li>
 * </ol>
 *
 * <p>It also pins the known gap: a paste created during the outage is written to
 * disk but never indexed. This is why readiness has to take the instance out of
 * service quickly.</p>
 */
class PostgresUpgradeAndOutageIT {

    private static final Duration READINESS_TIMEOUT = Duration.ofSeconds(60);

    private final HttpClient http = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1) // as behind the ingress; avoids h2c upgrade attempts
            .connectTimeout(Duration.ofSeconds(5)).build();
    private final List<String> flywayLog = new CopyOnWriteArrayList<>();

    private Path dataDir;
    private int pgPort;
    private EmbeddedPostgres postgres;
    private Bytebin bytebin;
    private String baseUrl;

    @BeforeEach
    void setUp() throws Exception {
        captureFlywayLog();
        this.dataDir = Files.createTempDirectory("bytebin-pg-it");
        Files.delete(this.dataDir); // zonky runs initdb into a missing directory
        this.pgPort = freePort();
        this.postgres = startPostgres();

        Path contentDir = Paths.get("content");
        if (Files.exists(contentDir)) {
            try (var files = Files.list(contentDir)) {
                for (Path p : files.toList()) Files.delete(p);
            }
        }
    }

    @AfterEach
    void tearDown() throws Exception {
        if (this.bytebin != null) this.bytebin.close();
        if (this.postgres != null) this.postgres.close();
    }

    @Test
    void upgradeColdStartAndOutageRecovery() throws Exception {
        String serverVersion = query("SHOW server_version");
        String expectedMajor = System.getProperty("postgres.it.version", "18").split("\\.")[0];
        assertTrue(serverVersion.startsWith(expectedMajor + "."), "running PostgreSQL " + serverVersion);

        // 1. existing deployment at schema version 1, with one indexed paste
        Flyway.configure().dataSource(db()).locations("classpath:db/migration")
                .defaultSchema("bytebin").schemas("bytebin").target("1").load().migrate();
        execute("INSERT INTO bytebin.content (key, content_type, expiry, last_modified, encoding, backend_id, content_length) " +
                "VALUES ('legacy1', 'text/plain', -1, 1, 'gzip', 'local', 5)");

        // 2. cold start migrates V1 -> latest
        startBytebin();
        assertEquals("2", query("SELECT max(version) FROM bytebin.flyway_schema_history WHERE success"));
        assertEquals("legacy1", query("SELECT key FROM bytebin.content WHERE key = 'legacy1'"), "existing metadata survives the migration");
        Thread.sleep(1000); // log4j is asynchronous; let Flyway's startup messages arrive
        assertTrue(flywayLog.stream().noneMatch(m -> m.contains("Flyway upgrade recommended") || m.contains("not been tested")),
                "Flyway must support PostgreSQL " + serverVersion + " without warnings, saw: " + flywayLog);
        assertEquals(200, get("/health/ready").statusCode());

        // 3. paste lifecycle while healthy
        HttpResponse<String> created = send(HttpRequest.newBuilder(uri("/post"))
                .header("Content-Type", "text/plain")
                .header("Allow-Modification", "true")
                .POST(HttpRequest.BodyPublishers.ofString("hello")));
        assertEquals(201, created.statusCode());
        String key = JsonParser.parseString(created.body()).getAsJsonObject().get("key").getAsString();
        String modificationKey = created.headers().firstValue("Modification-Key").orElseThrow();
        awaitTrue("paste indexed", () -> queryUnchecked("SELECT key FROM bytebin.content WHERE key = '" + key + "'") != null);
        assertEquals("hello", get("/" + key).body());

        assertEquals(200, put(key, modificationKey, "hello v2").statusCode());
        assertEquals("hello v2", get("/" + key).body());

        // 4. outage
        this.postgres.close();
        this.postgres = null;
        Instant outageStart = Instant.now();
        awaitStatus("/health/ready", 503);
        Duration toUnready = Duration.between(outageStart, Instant.now());
        assertTrue(toUnready.compareTo(Duration.ofSeconds(20)) <= 0, "readiness failed after " + toUnready);
        assertTrue(get("/metrics").body().contains("bytebin_database_ready 0.0"));
        assertEquals(200, get("/health").statusCode(), "liveness must not depend on the database");
        assertEquals("hello v2", get("/" + key).body(), "cached pastes still serve during the outage");

        HttpResponse<String> createdDuringOutage = send(HttpRequest.newBuilder(uri("/post"))
                .header("Content-Type", "text/plain")
                .POST(HttpRequest.BodyPublishers.ofString("written during outage")));
        assertEquals(201, createdDuringOutage.statusCode(), "the key is returned before the index write runs");
        String outageKey = JsonParser.parseString(createdDuringOutage.body()).getAsJsonObject().get("key").getAsString();
        awaitTrue("outage paste written to disk", () -> Files.exists(Paths.get("content", outageKey)));

        // 5. recovery on the same data directory
        this.postgres = startPostgres();
        awaitStatus("/health/ready", 200);
        assertTrue(get("/metrics").body().contains("bytebin_database_ready 1.0"));

        assertEquals("hello v2", get("/" + key).body());
        assertEquals(200, put(key, modificationKey, "hello v3").statusCode());
        assertEquals("hello v3", get("/" + key).body());
        awaitTrue("update after recovery reaches the index", () -> "1".equals(queryUnchecked(
                "SELECT count(*) FROM bytebin.content WHERE key = '" + key + "' AND last_modified > 1")));

        awaitTrue("usage events buffered during the outage are written after recovery", () -> "1".equals(queryUnchecked(
                "SELECT count(*) FROM bytebin.usage_events WHERE event_type = 'api_post' AND content_key = '" + outageKey + "'")));

        // Known gap: the index write failed during the outage and is not retried. The
        // bytes are on disk (an AuditTask orphan) but the paste is lost from the index,
        // so it stops resolving once the in-memory cache entry expires.
        assertNull(query("SELECT key FROM bytebin.content WHERE key = '" + outageKey + "'"));
        assertTrue(Files.exists(Paths.get("content", outageKey)));

        // a restart after recovery is a no-op migration
        String historyRows = query("SELECT count(*) FROM bytebin.flyway_schema_history");
        this.bytebin.close();
        this.bytebin = null;
        startBytebin();
        assertEquals("hello v3", get("/" + key).body());
        assertEquals(historyRows, query("SELECT count(*) FROM bytebin.flyway_schema_history"));
    }

    // --- helpers ---

    private EmbeddedPostgres startPostgres() throws IOException {
        return EmbeddedPostgres.builder()
                .setPort(this.pgPort)
                .setDataDirectory(this.dataDir)
                .setCleanDataDirectory(false)
                .start();
    }

    private DataSource db() {
        return this.postgres.getPostgresDatabase();
    }

    private void startBytebin() throws Exception {
        int httpPort = freePort();
        JsonObject json = new JsonObject();
        json.addProperty("host", "127.0.0.1");
        json.addProperty("port", httpPort);
        json.addProperty("dbHost", "localhost");
        json.addProperty("dbPort", this.pgPort);
        json.addProperty("dbName", "postgres");
        json.addProperty("dbUsername", "postgres");
        json.addProperty("dbPassword", "postgres");
        json.addProperty("dbPoolSize", 4);
        this.bytebin = new Bytebin(new Configuration(json));
        this.baseUrl = "http://127.0.0.1:" + httpPort;
    }

    private URI uri(String path) {
        return URI.create(this.baseUrl + path);
    }

    private HttpResponse<String> get(String path) throws Exception {
        return send(HttpRequest.newBuilder(uri(path)).GET());
    }

    private HttpResponse<String> put(String key, String modificationKey, String body) throws Exception {
        return send(HttpRequest.newBuilder(uri("/" + key))
                .header("Content-Type", "text/plain")
                .header("Authorization", "Bearer " + modificationKey)
                .PUT(HttpRequest.BodyPublishers.ofString(body)));
    }

    private HttpResponse<String> send(HttpRequest.Builder request) throws Exception {
        return this.http.send(request.timeout(Duration.ofSeconds(45)).build(), HttpResponse.BodyHandlers.ofString());
    }

    private void awaitStatus(String path, int status) throws Exception {
        awaitTrue(path + " returns " + status, () -> {
            try {
                return get(path).statusCode() == status;
            } catch (Exception e) {
                return false;
            }
        });
    }

    private static void awaitTrue(String what, BooleanSupplier condition) throws InterruptedException {
        Instant deadline = Instant.now().plus(READINESS_TIMEOUT);
        while (!condition.getAsBoolean()) {
            if (Instant.now().isAfter(deadline)) {
                throw new AssertionError("timed out waiting for: " + what);
            }
            Thread.sleep(250);
        }
    }

    private String query(String sql) throws Exception {
        try (Connection c = db().getConnection(); Statement s = c.createStatement(); ResultSet rs = s.executeQuery(sql)) {
            return rs.next() ? rs.getString(1) : null;
        }
    }

    private String queryUnchecked(String sql) {
        try {
            return query(sql);
        } catch (Exception e) {
            return null;
        }
    }

    private void execute(String sql) throws Exception {
        try (Connection c = db().getConnection(); Statement s = c.createStatement()) {
            s.execute(sql);
        }
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private void captureFlywayLog() {
        LoggerContext context = (LoggerContext) LogManager.getContext(false);
        AbstractAppender appender = new AbstractAppender("flyway-capture", null, null, true, Property.EMPTY_ARRAY) {
            @Override
            public void append(LogEvent event) {
                flywayLog.add(event.getMessage().getFormattedMessage());
            }
        };
        appender.start();
        context.getConfiguration().getLoggerConfig("org.flywaydb").addAppender(appender, Level.ALL, null);
        context.updateLoggers();
    }
}
