package me.lucko.bytebin.dao;

import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;

class UsageReportFilteringTest {
    @Test
    void excludesMonitoringFromEveryMetricWhileKeepingUnknownAgents() throws Exception {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:usage_filter;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE");
        try (var connection = dataSource.getConnection()) {
            connection.createStatement().execute("""
                    CREATE TABLE usage_events (
                        event_type VARCHAR, timestamp BIGINT, ip_address VARCHAR,
                        user_agent VARCHAR, content_length INTEGER)
                    """);
            connection.createStatement().execute("""
                    INSERT INTO usage_events VALUES
                    ('api_post', 100, 'human', 'Mozilla/5.0', 10),
                    ('api_get', 150, 'human', 'Mozilla/5.0', 0),
                    ('api_post', 100, 'unknown', NULL, 20),
                    ('ui_visit', 150, 'empty', '', 0),
                    ('api_post', 150, 'monitor', 'Mozilla/5.0+(compatible; UptimeRobot/2.0; http://www.uptimerobot.com/)', 1000),
                    ('api_get', 150, 'monitor2', 'UPTIMEROBOT/3.0', 0),
                    ('api_get', 150, 'monitor2', 'UPTIMEROBOT/3.0', 0),
                    ('api_post', 99, 'before', 'Mozilla/5.0', 500),
                    ('api_post', 200, 'after', 'Mozilla/5.0', 500)
                    """);
            Configuration configuration = new Configuration(
                    new Environment("test", new JdbcTransactionFactory(), dataSource));
            configuration.addMapper(UsageEventMapper.class);
            UsageEventDao dao = new UsageEventDao(new SqlSessionFactoryBuilder().build(configuration));

            assertEquals(4, dao.countTotal(100, 200, true));
            assertEquals(3, dao.countUniqueIps(100, 200, true));
            assertEquals(30, dao.sumContentBytesPosted(100, 200, true));
            var byType = dao.countByEventType(100, 200, true).stream().collect(Collectors.toMap(
                    row -> row.get("event_type"), row -> ((Number) row.get("count")).longValue()));
            assertEquals(Map.of("api_post", 2L, "api_get", 1L, "ui_visit", 1L), byType);
            var agents = dao.topUserAgents(100, 200, 5, true).stream().collect(Collectors.toMap(
                    row -> row.get("user_agent"), row -> ((Number) row.get("count")).longValue()));
            assertEquals(Map.of("Mozilla/5.0", 2L, "unknown", 1L, "", 1L), agents);
            assertEquals("Mozilla/5.0", dao.topUserAgents(100, 200, 1, true).getFirst().get("user_agent"));

            // Existing admin callers still include the stored monitoring traffic.
            assertEquals(7, dao.countTotal(100, 200));
            assertEquals(5, dao.countUniqueIps(100, 200));
            assertEquals(1030, dao.sumContentBytesPosted(100, 200));
        }
    }
}
