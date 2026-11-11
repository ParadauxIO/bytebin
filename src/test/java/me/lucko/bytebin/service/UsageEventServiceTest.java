package me.lucko.bytebin.service;

import me.lucko.bytebin.dao.UsageEventDao;
import me.lucko.bytebin.usage.UsageEvent;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.ScheduledThreadPoolExecutor;

import static org.junit.jupiter.api.Assertions.*;

class UsageEventServiceTest {
    @ParameterizedTest
    @ValueSource(strings = {"ui_visit", "key_visit", "api_get", "api_post", "api_put"})
    void monitoringEventsAreNeverPersisted(String eventType) throws Exception {
        var scheduler = new ScheduledThreadPoolExecutor(1);
        var dao = new RecordingDao();
        var service = new UsageEventService(dao, scheduler, 3600);
        try {
            for (String agent : List.of(
                    "Mozilla/5.0+(compatible; UptimeRobot/2.0; http://www.uptimerobot.com/)",
                    "UPTIMEROBOT/3.0", "kube-probe/1.30", "blackbox-exporter/0.25",
                    "Blackbox Exporter/0.25", "blackbox_exporter/0.25",
                    "Prometheus/2.0", "Go-http-client/1.1")) {
                assertTrue(UsageEventService.isSyntheticAgent(agent));
                service.record(UsageEvent.builder(eventType).userAgent(agent).build());
            }
            var browser = UsageEvent.builder(eventType).userAgent("Mozilla/5.0").build();
            var unknown = UsageEvent.builder(eventType).build();
            var empty = UsageEvent.builder(eventType).userAgent("").build();
            service.record(browser);
            service.record(unknown);
            service.record(empty);
            flush(service);
            assertEquals(List.of(browser, unknown, empty), dao.saved);
            service.close();
            assertEquals(List.of(browser, unknown, empty), dao.saved);
        } finally {
            scheduler.shutdownNow();
            service.close();
        }
    }


    @Test
    void daoPropagatesDatabaseFailure() {
        SqlSessionFactory factory = (SqlSessionFactory) Proxy.newProxyInstance(
                SqlSessionFactory.class.getClassLoader(), new Class<?>[]{SqlSessionFactory.class},
                (proxy, method, args) -> { throw new IllegalStateException("database unavailable"); });
        assertThrows(IllegalStateException.class,
                () -> new UsageEventDao(factory).insertBatch(List.of(UsageEvent.builder("api_get").build())));
    }

    @Test
    void outageIsBoundedAndPendingBatchIsRetriedBeforeNewEvents() throws Exception {
        var scheduler = new ScheduledThreadPoolExecutor(1);
        var dao = new RecordingDao();
        var service = new UsageEventService(dao, scheduler, 3600);
        try {
            var first = UsageEvent.builder("api_get").build();
            service.record(first);
            dao.fail = true;
            flush(service);
            for (int i = 0; i < 20000; i++) service.record(UsageEvent.builder("ui_visit").build());
            flush(service);
            dao.fail = false;
            for (int i = 0; i < 20; i++) flush(service);
            assertEquals(10001, dao.saved.size(), "retain pending batch plus bounded queue");
            assertSame(first, dao.saved.get(0));
            assertTrue(dao.largestBatch <= 1000, "stay below PostgreSQL parameter limit");
        } finally {
            scheduler.shutdownNow();
            service.close();
        }
    }

    @Test
    void shutdownDrainsMultipleBatches() {
        var scheduler = new ScheduledThreadPoolExecutor(1);
        var dao = new RecordingDao();
        var service = new UsageEventService(dao, scheduler, 3600);
        for (int i = 0; i < 2500; i++) service.record(UsageEvent.builder("api_get").build());
        scheduler.shutdownNow();
        service.close();
        assertEquals(2500, dao.saved.size());
        assertTrue(dao.largestBatch <= 1000);
    }

    private static void flush(UsageEventService service) throws Exception {
        var method = UsageEventService.class.getDeclaredMethod("flush");
        method.setAccessible(true);
        method.invoke(service);
    }

    private static class RecordingDao extends UsageEventDao {
        final List<UsageEvent> saved = new ArrayList<>();
        boolean fail;
        int largestBatch;
        RecordingDao() { super(null); }
        @Override public void insertBatch(Collection<UsageEvent> events) {
            largestBatch = Math.max(largestBatch, events.size());
            if (fail) throw new IllegalStateException("database unavailable");
            saved.addAll(events);
        }
    }
}
