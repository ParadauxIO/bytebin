package me.lucko.bytebin.content.storage;

import me.lucko.bytebin.content.Content;
import me.lucko.bytebin.util.ContentEncoding;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.Date;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * Simple storage backend that persists content to the local filesystem.
 */
public class LocalDiskBackend implements StorageBackend {

    private static final Logger LOGGER = LogManager.getLogger(LocalDiskBackend.class);

    /** The id of the backend */
    private final String backendId;

    /** The path to the directory where the content is stored */
    private final Path contentPath;

    public LocalDiskBackend(String backendId, Path contentPath) throws IOException {
        this.backendId = backendId;
        this.contentPath = contentPath;

        // initialise
        Files.createDirectories(this.contentPath);
        LOGGER.info("[DISK] Initialized LocalDiskBackend: id='{}', path='{}'", backendId, contentPath.toAbsolutePath());
    }

    @Override
    public String getBackendId() {
        return this.backendId;
    }

    private Content load(Path resolved, boolean skipContent) throws IOException {
        if (!Files.exists(resolved)) {
            return null;
        }

        try (DataInputStream in = new DataInputStream(new BufferedInputStream(Files.newInputStream(resolved)))) {
            Content content = read(in, skipContent);
            content.setBackendId(this.backendId);
            return content;
        }
    }

    @Override
    public Content load(String key) throws IOException {
        Path path = this.contentPath.resolve(key);
        LOGGER.debug("[DISK] Loading content: key={}", key);
        return load(path, false);
    }

    @Override
    public void save(Content c) throws IOException {
        Path path = this.contentPath.resolve(c.getKey());
        try (BufferedOutputStream out = new BufferedOutputStream(Files.newOutputStream(path))) {
            write(c, out);
        }
        LOGGER.debug("[DISK] Saved content: key={} size={} bytes", c.getKey(), c.getContent().length);
    }

    @Override
    public Stream<String> listKeys() throws Exception {
        return Files.list(this.contentPath).map(path -> path.getFileName().toString());
    }

    @Override
    public Stream<Content> list() throws IOException {
        return Files.list(this.contentPath)
                .map(path -> {
                    try {
                        return load(path, true);
                    } catch (IOException e) {
                        LOGGER.error("Exception occurred loading meta for '" + path.getFileName().toString() + "'", e);
                        return null;
                    }
                })
                .filter(Objects::nonNull);
    }

    @Override
    public void delete(String key) throws IOException {
        Path path = this.contentPath.resolve(key);
        try {
            Files.delete(path);
            LOGGER.debug("[DISK] Deleted content: key={}", key);
        } catch (NoSuchFileException e) {
            LOGGER.debug("[DISK] Delete skipped (file not found): key={}", key);
        }
    }

    private static void write(Content c, OutputStream outputStream) throws IOException {
        DataOutputStream out = new DataOutputStream(outputStream);

        // write version
        out.writeInt(2);

        // write name
        out.writeUTF(c.getKey());

        // write content type
        byte[] contextType = c.getContentType().getBytes();
        out.writeInt(contextType.length);
        out.write(contextType);

        // write expiry time
        out.writeLong(c.getExpiry() == null ? -1 : c.getExpiry().getTime());

        // write last modified
        out.writeLong(c.getLastModified());

        // write modifiable state data
        out.writeBoolean(c.isModifiable());
        if (c.isModifiable()) {
            out.writeUTF(c.getAuthKey());
        }

        // write encoding
        byte[] encoding = c.getEncoding().getBytes();
        out.writeInt(encoding.length);
        out.write(encoding);

        // write content
        out.writeInt(c.getContent().length);
        out.write(c.getContent());
    }

    private static Content read(InputStream inputStream, boolean skipContent) throws IOException {
        DataInputStream in = new DataInputStream(inputStream);

        // read version
        int version = in.readInt();

        // read key
        String key = in.readUTF();

        // read content type
        byte[] contentTypeBytes = new byte[in.readInt()];
        in.readFully(contentTypeBytes);
        String contentType = new String(contentTypeBytes);

        // read expiry
        long expiry = in.readLong();
        Date expiryDate = expiry == -1 ? null : new Date(expiry);

        // read last modified time
        long lastModified = in.readLong();

        // read modifiable state data
        boolean modifiable = in.readBoolean();
        String authKey = null;
        if (modifiable) {
            authKey = in.readUTF();
        }

        // read encoding
        String encoding;
        if (version == 1) {
            encoding = ContentEncoding.GZIP;
        } else {
            byte[] encodingBytes = new byte[in.readInt()];
            in.readFully(encodingBytes);
            encoding = new String(encodingBytes);
        }

        // read content
        int contentLength = in.readInt();

        if (skipContent) {
            Content content = new Content(key, contentType, expiryDate, lastModified, modifiable, authKey, encoding, Content.EMPTY_BYTES, -1);
            content.setContentLength(contentLength);
            return content;
        } else {
            byte[] content = new byte[contentLength];
            in.readFully(content);
            return new Content(key, contentType, expiryDate, lastModified, modifiable, authKey, encoding, content, -1);
        }

    }

}

