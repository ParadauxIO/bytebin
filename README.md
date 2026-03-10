<h1 align="center">
	<img
		alt="bytebin"
		src="./assets/banner.png">
</h1>

<p align="center">
  A fast, lightweight content storage service with custom expiry, read limits, and a modern web UI.
</p>

<p align="center">
  <b>Public instance:</b> <a href="https://pastes.paradaux.io">pastes.paradaux.io</a>
</p>

<!-- TODO: Add screenshot -->
<!-- ![Screenshot](./assets/screenshot.png) -->

## Features

- **Any content type** -- not just plain text. Upload binary data, JSON, images, whatever you want.
- **Custom expiry** -- set a per-upload expiry via the `Bytebin-Expiry` header (value in minutes). Defaults to 30 days.
- **Read-limited content** -- set a maximum read count via the `Bytebin-Max-Reads` header. Content is automatically deleted after N reads.
- **Modifiable uploads** -- optionally allow content to be updated via PUT requests with a modification key.
- **Compression** -- content is compressed with gzip to reduce disk usage and network load. Clients can also upload pre-compressed data.
- **Local disk storage** -- content bytes are stored on local disk, separate from the metadata index.
- **PostgreSQL metadata index** -- content metadata is stored in PostgreSQL via MyBatis, enabling horizontal scaling across multiple instances.
- **Modern web UI** -- dark-themed frontend with sidebar options for expiry, read limits, and a live preview.
- **Prometheus metrics** -- built-in metrics endpoint for monitoring storage, request rates, and database performance.
- **CORS support** -- full cross-origin resource sharing for API consumers.

## API

### Read

* Send a `GET` request to `/{key}` (e.g. `/aabbcc`).
  * The content will be returned as-is in the response body.
  * If the content was posted using an encoding other than gzip, the requester must also "accept" it.
  * For gzip, bytebin will automatically uncompress if the client doesn't support compression.
  * If the content has a read limit, the response includes a `Bytebin-Reads-Remaining` header.

### Write

* Send a `POST` request to `/post` with the content in the request body.
  * You should also specify `Content-Type` and `User-Agent` headers, but this is not required.
* Ideally, content should be compressed with GZIP or another mechanism before being uploaded.
  * Include the `Content-Encoding: <type>` header if this is the case.
  * bytebin will compress server-side using gzip if no encoding is specified - but it is better (for performance reasons) if the client does this instead.
* A unique key that identifies the content will be returned. You can find it:
  * In the response `Location` header.
  * In the response body, encoded as JSON - `{"key": "aabbcc"}`.

#### Optional Headers

| Header | Type | Description |
|---|---|---|
| `Bytebin-Expiry` | integer | Custom expiry time in **minutes**. Defaults to 30 days if omitted. |
| `Bytebin-Max-Reads` | integer | Maximum number of times the content can be read before automatic deletion. |
| `Allow-Modification` | boolean | If `true`, returns a `Modification-Key` header for subsequent PUT updates. |

## Self-hosting

### Requirements

- Java 21+
- PostgreSQL 15+

### Docker Compose

```yaml
services:
  postgres:
    image: postgres:17-alpine
    restart: unless-stopped
    environment:
      POSTGRES_DB: bytebin
      POSTGRES_USER: bytebin
      POSTGRES_PASSWORD: bytebin
    volumes:
      - pgdata:/var/lib/postgresql/data
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U bytebin -d bytebin"]
      interval: 5s
      timeout: 5s
      retries: 5

  bytebin:
    image: harbor.paradaux.io/paradaux-public/bytebin:prod
    ports:
      - 3000:8080
    volumes:
      - data:/opt/bytebin/content
    environment:
      BYTEBIN_DB_HOST: postgres
      BYTEBIN_DB_PORT: 5432
      BYTEBIN_DB_NAME: bytebin
      BYTEBIN_DB_USERNAME: bytebin
      BYTEBIN_DB_PASSWORD: bytebin
    depends_on:
      postgres:
        condition: service_healthy

volumes:
  data: {}
  pgdata: {}
```

```bash
$ docker compose up
```

You should then be able to access the application at `http://localhost:3000/`.

### Development

The included `docker-compose.yml` builds from the local Dockerfile and starts a PostgreSQL instance alongside it. To get a development environment running:

```bash
$ docker compose up --build
```

This will compile the project inside Docker (requires no local JDK) and start bytebin on `http://localhost:8080/` with a PostgreSQL database. The compose file is configured with sensible defaults for local development -- changes to the source just need another `--build`.

#### Project structure

```
me.lucko.bytebin
├── Bytebin.java                  # Entrypoint. Wires up HikariCP, Flyway, MyBatis,
│                                 # storage backends, and the Jooby web server.
├── content/
│   ├── Content.java              # POJO representing a piece of stored content (metadata + bytes).
│   ├── ContentIndexDatabase.java # All database access. Wraps MyBatis SqlSessionFactory.
│   ├── ContentMapper.java        # MyBatis mapper interface (Java methods -> SQL).
│   ├── ContentStorageHandler.java# Coordinates between the index DB and storage backends.
│   │                             # Implements Caffeine's CacheLoader for async loading.
│   ├── ContentLoader.java        # Caffeine cache layer sitting in front of ContentStorageHandler.
│   ├── StorageBackendSelector.java # Strategy for choosing which backend to write to.
│   ├── ContentStorageMetric.java # POJO for aggregate metric query results.
│   ├── DateEpochMillisTypeHandler.java # MyBatis TypeHandler: java.util.Date <-> BIGINT epoch millis.
│   └── storage/
│       ├── StorageBackend.java   # Interface: load, save, delete, list.
│       ├── LocalDiskBackend.java # Stores content bytes on local disk.
│       └── AuditTask.java        # Reconciles the DB index against storage backends.
├── http/
│   ├── BytebinServer.java        # Jooby app definition. Registers routes, CORS, error handlers.
│   ├── GetHandler.java           # GET /{key} -- serves content, handles read limits and expiry.
│   ├── PostHandler.java          # POST /post -- accepts uploads, compresses, saves.
│   ├── UpdateHandler.java        # PUT /{key} -- modifies existing content with auth key.
│   ├── MetricsHandler.java       # GET /metrics -- Prometheus scrape endpoint.
│   ├── MetricsFilter.java        # Route decorator that records per-request Prometheus metrics.
│   └── admin/
│       └── BulkDeleteHandler.java# POST /admin/delete -- bulk deletion with API key auth.
├── ratelimit/
│   ├── RateLimiter.java          # Interface for rate limiting strategies.
│   ├── SimpleRateLimiter.java    # Fixed-window rate limiter (POST, PUT, GET).
│   ├── ExponentialRateLimiter.java # Exponential backoff limiter (404 abuse prevention).
│   └── RateLimitHandler.java     # Extracts client IP and applies rate limits.
├── logging/
│   ├── LogHandler.java           # Interface for request audit logging.
│   ├── HttpLogHandler.java       # Ships audit logs to an external HTTP endpoint.
│   └── AbstractAsyncLogHandler.java # Async batching base class for log handlers.
└── util/
    ├── Configuration.java        # Reads config from JSON file, system properties, or env vars.
    ├── ContentEncoding.java      # Parses Accept-Encoding / Content-Encoding headers.
    ├── EnvVars.java              # Maps BYTEBIN_* env vars to system properties at startup.
    ├── ExceptionHandler.java     # Global uncaught exception handler.
    ├── ExpiryHandler.java        # Computes expiry times, with per-User-Agent overrides.
    ├── Gzip.java                 # Gzip compress/decompress helpers.
    ├── Metrics.java              # Prometheus metric definitions (counters, gauges, histograms).
    └── TokenGenerator.java       # Generates random content keys.
```

Resources:

```
src/main/resources/
├── db/migration/
│   └── V1__create_content_table.sql  # Flyway migration: creates bytebin schema and content table.
├── me/lucko/bytebin/content/
│   └── ContentMapper.xml             # MyBatis SQL mappings (queries, upserts, atomic increment).
├── mybatis-config.xml                # MyBatis settings, type aliases, type handler registration.
├── log4j2.xml                        # Log4j2 configuration.
└── www/                              # Static frontend assets (HTML, favicon).
```

#### Design decisions

**Database is only an index.** The PostgreSQL database stores metadata (key, content type, expiry, encoding, backend ID, size, read count) but never the actual content bytes. Bytes live in the local disk storage backend. This means the database is small and the index can be rebuilt from the backend at any time.

**Layered content access.** HTTP handlers never touch the database or storage backends directly. The call chain is: `Handler -> ContentLoader (Caffeine cache) -> ContentStorageHandler -> StorageBackend + ContentIndexDatabase`. This keeps each layer focused on one concern and makes it straightforward to test or replace any piece.

**Atomic read counting.** Read-limited content uses PostgreSQL's `UPDATE ... SET read_count = read_count + 1 ... RETURNING read_count` to atomically increment and return the count in a single round trip. This is safe across multiple application instances without any distributed locking.

**MyBatis over JPA/Hibernate.** The data model is a single table with no relationships. MyBatis lets us write exact SQL (including PostgreSQL-specific `ON CONFLICT` and `RETURNING` clauses) without the overhead of an ORM. The mapper XML and interface are in the `content` package alongside the code that uses them.

**Flyway for schema management.** Migrations live in `src/main/resources/db/migration/` and run automatically on startup before MyBatis is initialised. Adding a new migration is just adding a `V{n}__description.sql` file.

**Storage backend selection.** `StorageBackendSelector` is an interface that picks which backend to write to. Currently only local disk is supported. Adding a new backend means implementing the `StorageBackend` interface and wiring up a selector.

**Configuration resolution order.** Every config option is checked in order: Java system property -> environment variable -> JSON config file -> default value. Environment variables follow the `BYTEBIN_` prefix convention (e.g. `BYTEBIN_DB_HOST`). This makes it easy to configure in containers without mounting config files.

#### Contributing

- The project requires **Java 21+** to compile. The Docker build uses Eclipse Temurin 25. If you want to compile locally, install JDK 21 or newer.
- All database changes go through **Flyway migrations**. Never modify an existing migration that has been released -- create a new `V{n}__description.sql` file instead.
- Database access is confined to `ContentIndexDatabase` and `ContentMapper`. HTTP handlers should go through `ContentStorageHandler`, not the database directly.
- New SQL goes in `ContentMapper.xml` with a corresponding method in `ContentMapper.java`.
- New configuration options go in `Configuration.Option` and follow the existing naming convention.
- Keep HTTP handler logic thin -- business logic belongs in the `content` package.

### Configuration

bytebin is configured via environment variables. All variables follow the `BYTEBIN_*` pattern.

| Variable | Default | Description |
|---|---|---|
| `BYTEBIN_HTTP_HOST` | `0.0.0.0` | Bind address |
| `BYTEBIN_HTTP_PORT` | `8080` | Listen port |
| `BYTEBIN_DB_HOST` | `localhost` | PostgreSQL host |
| `BYTEBIN_DB_PORT` | `5432` | PostgreSQL port |
| `BYTEBIN_DB_NAME` | `bytebin` | PostgreSQL database name |
| `BYTEBIN_DB_USERNAME` | `bytebin` | PostgreSQL username |
| `BYTEBIN_DB_PASSWORD` | `bytebin` | PostgreSQL password |
| `BYTEBIN_DB_POOL_SIZE` | `10` | Connection pool size |
| `BYTEBIN_CONTENT_MAXSIZE` | `10` | Max upload size in MB |
| `BYTEBIN_CONTENT_EXPIRY` | `-1` | Default max content lifetime in minutes (-1 = no limit) |
| `BYTEBIN_MISC_KEYLENGTH` | `7` | Length of generated content keys |
| `BYTEBIN_METRICS_ENABLED` | `true` | Enable Prometheus metrics at `/metrics` |

## License

MIT -- see [LICENSE](LICENSE) for the full text.

---

bytebin was originally created by [lucko (Luck)](https://github.com/lucko). This project is a fork of [lucko/bytebin](https://github.com/lucko/bytebin) and retains his MIT license. Significant portions of the original codebase have been rewritten, but the core architecture and design are his work. Thank you, lucko.
