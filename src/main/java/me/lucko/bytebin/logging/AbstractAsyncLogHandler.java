package me.lucko.bytebin.logging;

import com.google.common.util.concurrent.ThreadFactoryBuilder;

import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public abstract class AbstractAsyncLogHandler implements LogHandler {
    private final Queue<Event> queue = new ConcurrentLinkedQueue<>();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(
            new ThreadFactoryBuilder().setNameFormat("bytebin-log-handler-%d").build()
    );

    public AbstractAsyncLogHandler(int flushIntervalSeconds) {
        this.scheduler.scheduleAtFixedRate(this::exportAndFlush, flushIntervalSeconds, flushIntervalSeconds, TimeUnit.SECONDS);
    }

    private void exportAndFlush() {
        List<Event> events = new ArrayList<>();
        for (Event e; (e = this.queue.poll()) != null; ) {
            events.add(e);
        }
        flush(events);
    }

    public abstract void flush(List<Event> events);

    @Override
    public void close() {
        exportAndFlush();
        this.scheduler.shutdown();
    }

    @Override
    public void logAttemptedGet(String key, User user) {
        this.queue.offer(new AttemptedGetEvent(key, user));
    }

    @Override
    public void logGet(String key, User user, ContentInfo content) {
        this.queue.offer(new GetEvent(key, user, content));
    }

    @Override
    public void logPost(String key, User user, ContentInfo content) {
        this.queue.offer(new PostEvent(key, user, content));
    }

    public static abstract class Event {
        private final String kind;
        private final long timestamp = System.currentTimeMillis();

        public Event(String kind) {
            this.kind = kind;
        }
    }

    public static final class AttemptedGetEvent extends Event {
        private final String key;
        private final User user;

        public AttemptedGetEvent(String key, User user) {
            super("attempted-get");
            this.key = key;
            this.user = user;
        }
    }

    public static final class GetEvent extends Event {
        private final String key;
        private final User user;
        private final ContentInfo content;

        public GetEvent(String key, User user, ContentInfo content) {
            super("get");
            this.key = key;
            this.user = user;
            this.content = content;
        }
    }

    public static final class PostEvent extends Event {
        private final String key;
        private final User user;
        private final ContentInfo content;

        public PostEvent(String key, User user, ContentInfo content) {
            super("post");
            this.key = key;
            this.user = user;
            this.content = content;
        }
    }

}
