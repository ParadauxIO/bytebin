package me.lucko.bytebin.util;

import com.google.common.io.ByteStreams;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

public final class Gzip {

    private static final Logger LOGGER = LogManager.getLogger(Gzip.class);

    private Gzip() {}

    public static byte[] compress(byte[] buf) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(buf.length);
        try (GZIPOutputStream gzipOut = new GZIPOutputStream(out)) {
            gzipOut.write(buf);
        } catch (IOException e) {
            LOGGER.error("Failed to gzip compress {} bytes", buf.length, e);
            throw new RuntimeException(e);
        }
        return out.toByteArray();
    }

    public static byte[] decompress(byte[] buf) throws IOException {
        ByteArrayInputStream in = new ByteArrayInputStream(buf);
        try (GZIPInputStream gzipIn = new GZIPInputStream(in)) {
            return ByteStreams.toByteArray(gzipIn);
        }
    }

}
