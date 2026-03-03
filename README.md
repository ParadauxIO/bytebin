<h1 align="center">
	<img
		alt="bytebin"
		src="./assets/banner.png">
</h1>

<h3 align="center">
  A fork of <a href="https://github.com/lucko/bytebin">lucko/bytebin</a> with additional features.
</h3>

bytebin is a fast & lightweight content storage web service, originally created by [lucko](https://github.com/lucko). This fork adds support for **custom expiry times**, **read-limited content**, and a **modernised web frontend**.

You can think of bytebin a bit like a [pastebin](https://en.wikipedia.org/wiki/Pastebin), except that it accepts any kind of data (not just plain text!).  
Accordingly, the name 'bytebin' is a portmanteau of "byte" (binary) and "pastebin".

### What's different in this fork?

* **Custom expiry** — clients can set a per-upload expiry via the `Bytebin-Expiry` header (value in minutes). Defaults to 30 days if not specified.
* **Read-limited content** — clients can set a maximum read count via the `Bytebin-Max-Reads` header. Content is automatically deleted after N reads.
* **Expiry enforcement on access** — expired content is rejected immediately on GET, not just during the background cleanup.
* **Faster cleanup** — the background invalidation task now runs every minute (previously 5 minutes and conditionally).
* **Modern UI** — redesigned frontend with a dark theme, sidebar options for expiry and read limits, and a live preview summary.

bytebin is:

* **fast** & (somewhat) **lightweight** - the focus is on the speed at which HTTP requests can be handled.
  * relatively *low* CPU usage
  * relatively *high* memory usage (content is cached in memory by default, but this can be disabled)
* **standalone** - it's just a simple Java app that listens for HTTP requests on a given port.
* **efficient** - utilises compression to reduce disk usage and network load.
* **flexible** - supports *any* content type or encoding. (and CORS too!)
* **easy to use** - simple HTTP API and a minimal HTML frontend.

## Running bytebin

The easiest way to spin up a bytebin instance is using Docker.

Minimal Docker Compose example:

```yaml
services:
  bytebin:
    image: ghcr.io/lucko/bytebin  # or your own Harbor/registry image
    ports:
      - 3000:8080
    volumes:
      - data:/opt/bytebin/content
      - db:/opt/bytebin/db
    environment:
      BYTEBIN_MISC_KEYLENGTH: 15
      BYTEBIN_CONTENT_MAXSIZE: 5

volumes:
  data: {}
  db: {}
```

```bash
$ docker compose up
```

You should then (hopefully!) be able to access the application at `http://localhost:3000/`.

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

## License

MIT, have fun!

*Originally created by [lucko](https://github.com/lucko/bytebin).*
