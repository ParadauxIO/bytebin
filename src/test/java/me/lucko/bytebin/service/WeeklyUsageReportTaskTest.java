package me.lucko.bytebin.service;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.Duration;
import java.time.ZonedDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;

class WeeklyUsageReportTaskTest {
    @ParameterizedTest
    @CsvSource({
            "2026-11-09T07:59:00Z, 2026-11-09T08:00:00Z",
            "2026-11-09T08:00:00Z, 2026-11-16T08:00:00Z",
            "2026-11-09T09:00:00Z, 2026-11-16T08:00:00Z",
            "2026-11-10T12:00:00Z, 2026-11-16T08:00:00Z",
            "2026-03-02T08:00:00-05:00[America/New_York], 2026-03-09T08:00:00-04:00[America/New_York]",
            "2026-10-26T08:00:00-04:00[America/New_York], 2026-11-02T08:00:00-05:00[America/New_York]"
    })
    void schedulesNextMondayAtEight(String current, String expected) {
        ZonedDateTime now = ZonedDateTime.parse(current);
        long delay = WeeklyUsageReportTask.computeDelayUntilNextReport(now);
        assertEquals(ZonedDateTime.parse(expected), now.plus(Duration.ofMillis(delay)));
    }
}
