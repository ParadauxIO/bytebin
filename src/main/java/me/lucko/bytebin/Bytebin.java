package me.lucko.bytebin;

import com.google.common.collect.ImmutableSet;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.jooby.ExecutionMode;
import io.jooby.Jooby;
import io.jooby.Server;
import io.jooby.ServerOptions;
import io.jooby.jetty.JettyServer;
import io.prometheus.client.hotspot.DefaultExports;
import me.lucko.bytebin.content.Content;
import me.lucko.bytebin.content.ContentIndexDatabase;
import me.lucko.bytebin.content.ContentLoader;
import me.lucko.bytebin.content.ContentStorageHandler;
import me.lucko.bytebin.content.StorageBackendSelector;
import me.lucko.bytebin.content.storage.AuditTask;
import me.lucko.bytebin.content.storage.LocalDiskBackend;
import me.lucko.bytebin.content.storage.StorageBackend;
import me.lucko.bytebin.http.BytebinServer;
import me.lucko.bytebin.logging.HttpLogHandler;
import me.lucko.bytebin.logging.LogHandler;
import me.lucko.bytebin.ratelimit.ExponentialRateLimiter;
import me.lucko.bytebin.ratelimit.RateLimitHandler;
import me.lucko.bytebin.ratelimit.SimpleRateLimiter;
import me.lucko.bytebin.util.Configuration;
import me.lucko.bytebin.util.Configuration.Option;
import me.lucko.bytebin.util.EnvVars;
import me.lucko.bytebin.util.ExceptionHandler;
import me.lucko.bytebin.util.ExpiryHandler;
import me.lucko.bytebin.util.TokenGenerator;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.io.IoBuilder;
import org.flywaydb.core.Flyway;

import java.io.InputStream;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Stupidly simple "pastebin" service.
 */
public final class Bytebin implements AutoCloseable {

    /** Logger instance */
    private static final Logger LOGGER;

    static {
        EnvVars.read();
        LOGGER = LogManager.getLogger(Bytebin.class);
    }

    // Bootstrap
    public static void main(String[] args) throws Exception {
        // setup logging
        System.setOut(IoBuilder.forLogger(LOGGER).setLevel(Level.INFO).buildPrintStream());
        System.setErr(IoBuilder.forLogger(LOGGER).setLevel(Level.ERROR).buildPrintStream());

        // setup a new bytebin instance
        Configuration config = Configuration.load(Paths.get("config.json"));
        try {
            Bytebin bytebin = new Bytebin(config);
            Runtime.getRuntime().addShutdownHook(new Thread(bytebin::close, "Bytebin Shutdown Thread"));
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /** Executor service for performing file based i/o */
    private final ScheduledExecutorService executor;

    private final HikariDataSource dataSource;
    private final ContentIndexDatabase indexDatabase;
    private final LogHandler logHandler;

    /** The web server instance */
    private final Server server;

    public Bytebin(Configuration config) throws Exception {
        // setup simple logger
        LOGGER.info("loading bytebin...");

        // setup executor
        Thread.setDefaultUncaughtExceptionHandler(ExceptionHandler.INSTANCE);
        this.executor = Executors.newScheduledThreadPool(
                config.getInt(Option.EXECUTOR_POOL_SIZE, 64),
                new ThreadFactoryBuilder().setNameFormat("bytebin-io-%d").build()
        );

        // setup storage backends
        List<StorageBackend> storageBackends = new ArrayList<>();

        LocalDiskBackend localDiskBackend = new LocalDiskBackend("local", Paths.get("content"));
        storageBackends.add(localDiskBackend);

        StorageBackendSelector backendSelector = new StorageBackendSelector.Static(localDiskBackend);

        // setup PostgreSQL connection pool
        String dbHost = config.getString(Option.DB_HOST, "localhost");
        int dbPort = config.getInt(Option.DB_PORT, 5432);
        String dbName = config.getString(Option.DB_NAME, "bytebin");
        String dbUsername = config.getString(Option.DB_USERNAME, "bytebin");
        String dbPassword = config.getString(Option.DB_PASSWORD, "bytebin");
        int dbPoolSize = config.getInt(Option.DB_POOL_SIZE, 10);

        HikariConfig hikariConfig = new HikariConfig();
        hikariConfig.setJdbcUrl("jdbc:postgresql://" + dbHost + ":" + dbPort + "/" + dbName);
        hikariConfig.setUsername(dbUsername);
        hikariConfig.setPassword(dbPassword);
        hikariConfig.setMaximumPoolSize(dbPoolSize);
        hikariConfig.setPoolName("bytebin-db");
        // Recommended PostgreSQL HikariCP settings
        hikariConfig.addDataSourceProperty("cachePrepStmts", "true");
        hikariConfig.addDataSourceProperty("prepStmtCacheSize", "250");
        hikariConfig.addDataSourceProperty("prepStmtCacheSqlLimit", "2048");

        hikariConfig.setSchema("bytebin");
        hikariConfig.setConnectionInitSql("SET search_path TO bytebin, public");

        this.dataSource = new HikariDataSource(hikariConfig);
        LOGGER.info("[DB] Connected to PostgreSQL at {}:{}/{}", dbHost, dbPort, dbName);

        // run Flyway migrations
        Flyway flyway = Flyway.configure()
                .dataSource(this.dataSource)
                .locations("classpath:db/migration")
                .defaultSchema("bytebin")
                .schemas("bytebin", "public")
                .load();
        flyway.migrate();
        LOGGER.info("[DB] Flyway migrations applied successfully");

        // setup MyBatis
        org.apache.ibatis.session.Configuration mybatisConfig;
        try (InputStream is = Bytebin.class.getClassLoader().getResourceAsStream("mybatis-config.xml")) {
            SqlSessionFactory factory = new SqlSessionFactoryBuilder().build(is);
            mybatisConfig = factory.getConfiguration();
        }
        // Replace the default environment with one using our HikariCP DataSource
        Environment mybatisEnv = new Environment("production", new JdbcTransactionFactory(), this.dataSource);
        mybatisConfig.setEnvironment(mybatisEnv);
        SqlSessionFactory sqlSessionFactory = new SqlSessionFactoryBuilder().build(mybatisConfig);

        this.indexDatabase = ContentIndexDatabase.initialise(sqlSessionFactory, storageBackends);

        ContentStorageHandler storageHandler = new ContentStorageHandler(this.indexDatabase, storageBackends, backendSelector, this.executor);

        ContentLoader contentLoader = ContentLoader.create(
                storageHandler,
                config.getInt(Option.CACHE_EXPIRY, 10),
                config.getInt(Option.CACHE_MAX_SIZE, 200)
        );

        ExpiryHandler expiryHandler = new ExpiryHandler(
                config.getLong(Option.MAX_CONTENT_LIFETIME, -1), // never expire by default
                config.getLongMap(Option.MAX_CONTENT_LIFETIME_USER_AGENTS)
        );

        boolean metrics = config.getBoolean(Option.METRICS, true);
        if (metrics) {
            DefaultExports.initialize();
        }

        String loggingHttpUri = config.getString(Option.LOGGING_HTTP_URI, null);
        this.logHandler = loggingHttpUri != null
                ? new HttpLogHandler(loggingHttpUri, config.getInt(Option.LOGGING_HTTP_FLUSH_PERIOD, 10))
                : new LogHandler.Stub();

        long maxContentLength = Content.MEGABYTE_LENGTH * config.getInt(Option.MAX_CONTENT_LENGTH, 10);
        String localAssetPath = config.getString(Option.LOCAL_ASSET_PATH, null);

        // setup the web server
        ServerOptions serverOpts = new ServerOptions();
        serverOpts.setHost(config.getString(Option.HOST, "0.0.0.0"));
        serverOpts.setPort(config.getInt(Option.PORT, 8080));
        serverOpts.setCompressionLevel(null);
        serverOpts.setMaxRequestSize((int) maxContentLength);
        serverOpts.setIoThreads(config.getInt(Option.IO_THREADS, 16));
        serverOpts.setWorkerThreads(config.getInt(Option.EXECUTOR_POOL_SIZE, 64));

        ExecutionMode executionMode = ExecutionMode.valueOf(
                config.getString(Option.EXECUTION_MODE, "WORKER").toUpperCase(Locale.ROOT)
        );

        this.server = new JettyServer(serverOpts);
        this.server.start(Jooby.createApp(this.server, executionMode, () -> new BytebinServer(
                storageHandler,
                contentLoader,
                this.logHandler,
                metrics,
                new RateLimitHandler(config.getStringList(Option.RATELIMIT_API_KEYS)),
                /* POST rate limit */
                new SimpleRateLimiter(
                        // by default, allow posts at a rate of 30 times every 10 minutes (every 20s)
                        config.getInt(Option.POST_RATE_LIMIT, 30),
                        config.getInt(Option.POST_RATE_LIMIT_PERIOD, 10)
                ),
                /* PUT rate limit */
                new SimpleRateLimiter(
                        // by default, allow updates at a rate of 30 times every 5 minutes (every 10s)
                        config.getInt(Option.UPDATE_RATE_LIMIT, 30),
                        config.getInt(Option.UPDATE_RATE_LIMIT_PERIOD, 5)
                ),
                /* GET rate limit */
                new SimpleRateLimiter(
                        // by default, allow reads at a rate of 30 times every 2 minutes (every 4s)
                        config.getInt(Option.READ_RATE_LIMIT, 30),
                        config.getInt(Option.READ_RATE_LIMIT_PERIOD, 2)
                ),
                /* GET notfound/404 rate limit */
                new ExponentialRateLimiter(
                        // by default, allow notfound/404 reads at a rate of 10 times every 10 minutes (every 1m), with a x2 multiplier up to a max of 24 hours
                        config.getInt(Option.READ_NOTFOUND_RATE_LIMIT, 10),
                        config.getInt(Option.READ_NOTFOUND_RATE_LIMIT_PERIOD, 10),
                        config.getDouble(Option.READ_NOTFOUND_RATE_LIMIT_PERIOD_MULTIPLIER, 2.0),
                        config.getInt(Option.READ_NOTFOUND_RATE_LIMIT_PERIOD_MAX, 1440) // 24 hours
                ),
                new TokenGenerator(config.getInt(Option.KEY_LENGTH, 7)),
                maxContentLength,
                expiryHandler,
                config.getStringMap(Option.HTTP_HOST_ALIASES),
                ImmutableSet.copyOf(config.getStringList(Option.ADMIN_API_KEYS)),
                localAssetPath != null ? Paths.get(localAssetPath) : null
        )));

        // schedule invalidation task
        // always run: even without server-configured expiry times, users can set custom expiry via headers
        this.executor.scheduleWithFixedDelay(storageHandler::runInvalidationAndRecordMetrics, 1, 1, TimeUnit.MINUTES);

        if (config.getBoolean(Option.AUDIT_ON_STARTUP, false)) {
            this.executor.execute(new AuditTask(this.indexDatabase, storageBackends));
        }
    }

    @Override
    public void close() {
        this.server.stop();
        this.executor.shutdown();
        try {
            this.executor.awaitTermination(30, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            LOGGER.error("Exception whilst shutting down executor", e);
        }
        try {
            this.indexDatabase.close();
        } catch (Exception e) {
            LOGGER.error("Exception whilst shutting down index database", e);
        }
        try {
            this.dataSource.close();
            LOGGER.info("[DB] Connection pool closed");
        } catch (Exception e) {
            LOGGER.error("Exception whilst shutting down database connection pool", e);
        }
        this.logHandler.close();
    }

}
