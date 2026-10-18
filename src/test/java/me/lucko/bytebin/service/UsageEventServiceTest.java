package me.lucko.bytebin.service;

import me.lucko.bytebin.dao.UsageEventDao;
import me.lucko.bytebin.usage.UsageEvent;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.ScheduledThreadPoolExecutor;

import static org.junit.jupiter.api.Assertions.*;

class UsageEventServiceTest {
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
