package cn.code91.toolbox.database.integration;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import cn.code91.toolbox.database.observability.P6spyDataSourceWrappingBeanPostProcessor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>p6spy BPP 真实包装 DataSource 后 SQL 日志产出验证（Testcontainers PostgreSQL）</b>
 * <p>与单测（{@code P6spyDataSourceWrappingBeanPostProcessorTest}）的区别：单测只验证
 * "返回值是 P6DataSource 类型"这一结构性事实；本 IT 验证经包装后的 DataSource 在真实执行
 * SQL 时，p6spy 确实按 {@code spy.properties} 配置把 SQL 文本输出到 SLF4J（brief 明确要求
 * "SQL 日志产出可验证"）。</p>
 */
@DisplayName("p6spy BPP 真实包装 DataSource 后 SQL 日志产出（IT）")
class PgP6spyIT extends AbstractPostgresIntegrationTest {

    private static final String P6SPY_LOGGER_NAME = "p6spy";

    private ListAppender<ILoggingEvent> listAppender;
    private Logger p6spyLogger;

    @BeforeEach
    void attachListAppender() {
        p6spyLogger = (Logger) LoggerFactory.getLogger(P6SPY_LOGGER_NAME);
        listAppender = new ListAppender<>();
        listAppender.start();
        p6spyLogger.addAppender(listAppender);
    }

    @AfterEach
    void detachListAppender() {
        if (p6spyLogger != null && listAppender != null) {
            p6spyLogger.detachAppender(listAppender);
        }
    }

    @Test
    @DisplayName("BPP 包装后的 DataSource 执行 SQL，p6spy 输出该 SQL 文本到日志")
    void wrappedDataSourceLogsExecutedSql() throws SQLException {
        SimpleDriverDataSource plain = new SimpleDriverDataSource();
        plain.setDriverClass(org.postgresql.Driver.class);
        plain.setUrl(POSTGRES.getJdbcUrl());
        plain.setUsername(POSTGRES.getUsername());
        plain.setPassword(POSTGRES.getPassword());

        P6spyDataSourceWrappingBeanPostProcessor bpp = new P6spyDataSourceWrappingBeanPostProcessor();
        DataSource wrapped = (DataSource) bpp.postProcessAfterInitialization(plain, "dataSource");

        String marker = "p6spy_marker_probe";
        try (Connection connection = wrapped.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("SELECT '" + marker + "' AS probe_column");
        }

        assertThat(listAppender.list)
                .as("p6spy 应把执行过的 SQL 文本输出到日志（含标记串 %s）", marker)
                .anyMatch(event -> event.getFormattedMessage().contains(marker));
    }
}
