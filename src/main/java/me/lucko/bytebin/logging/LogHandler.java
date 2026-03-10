package me.lucko.bytebin.logging;

import java.util.Date;
import java.util.Map;

public interface LogHandler extends AutoCloseable {

    void logAttemptedGet(String key, User user);

    void logGet(String key, User user, ContentInfo contentInfo);

    void logPost(String key, User user, ContentInfo contentInfo);

    @Override
    void close();

    class Stub implements LogHandler {
        @Override
        public void logAttemptedGet(String key, User user) {

        }

        @Override
        public void logGet(String key, User user, ContentInfo contentInfo) {

        }

        @Override
        public void logPost(String key, User user, ContentInfo contentInfo) {

        }

        @Override
        public void close() {

        }
    }

    record User(String userAgent, String origin, String host, String ip, Map<String, String> headers) {}
    record ContentInfo(int contentLength, String contentType, Date contentExpiry) {}

}
