package cn.code91.toolbox.database.integration;

import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * <b>共享 Testcontainers PostgreSQL fixture（单例容器模式）</b>
 * <p>所有 IT 类继承本 class 共享<b>同一个</b>容器实例：容器在静态初始化块启动一次，
 * 整个 JVM 生命周期内不停（Testcontainers Ryuk 在 JVM 退出时回收）。若改用
 * per-class 生命周期（{@code @Container} 非 static 字段），当模块内 &gt;1 个 IT 类时，
 * 第一个 IT 类跑完后容器 stop，第二个 IT 类若复用了任何缓存的连接池/元数据则会遇到
 * "Connection refused"（beacon-database phase-8 R6 的实测教训）——本模块虽然当前只有两个
 * IT 类且各自独立建连接，暂无该问题复现条件，但仍采用同一稳妥模式，避免未来新增 IT 类时
 * 引入该坑。</p>
 *
 * <p>{@code disabledWithoutDocker = true}：无 Docker 环境时整个 IT 类跳过，不影响
 * 无 Docker 环境下的常规 {@code mvn test}。</p>
 */
@Testcontainers(disabledWithoutDocker = true)
public abstract class AbstractPostgresIntegrationTest {

    protected static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("toolbox_database_it")
            .withUsername("toolbox_it")
            .withPassword("toolbox_it");

    static {
        POSTGRES.start();
    }
}
