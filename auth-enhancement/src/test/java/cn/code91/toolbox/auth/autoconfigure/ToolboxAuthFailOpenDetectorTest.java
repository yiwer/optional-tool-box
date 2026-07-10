package cn.code91.toolbox.auth.autoconfigure;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.LazyInitializationBeanFactoryPostProcessor;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.core.env.MapPropertySource;
import org.springframework.security.oauth2.jwt.JwtDecoder;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * fail-open 探测器（07 §5.5，R6/R7）：缺引擎类 + toolbox.auth.* 配置在场 ⇒ 启动 WARN；
 * enabled=false 显式退出静默；无键静默；类在场/非 servlet 整体退让。WARN 经内存 appender
 * 断言（llm logging 测试先例），setAdditive(false) 保持测试输出干净。
 */
@DisplayName("ToolboxAuthFailOpenDetector fail-open 探测")
class ToolboxAuthFailOpenDetectorTest {

    private Logger detectorLogger;
    private boolean originalAdditive;
    private ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void captureLog() {
        detectorLogger = (Logger) LoggerFactory.getLogger(ToolboxAuthFailOpenDetector.class);
        originalAdditive = detectorLogger.isAdditive();
        appender = new ListAppender<>();
        appender.start();
        detectorLogger.setAdditive(false);
        detectorLogger.addAppender(appender);
    }

    @AfterEach
    void restoreLog() {
        detectorLogger.detachAppender(appender);
        detectorLogger.setAdditive(originalAdditive);
    }

    /** 缺引擎类的 servlet web runner（探测器主场景）。 */
    private WebApplicationContextRunner missingEngineRunner() {
        return new WebApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(ToolboxAuthFailOpenDetector.class))
                .withClassLoader(new FilteredClassLoader(JwtDecoder.class));
    }

    @Test
    void warnsWhenConfigPresentButEngineMissing() {
        missingEngineRunner()
                .withPropertyValues("toolbox.auth.keycloak.server-url=https://kc.example.com")
                .run(context -> {
                    assertThat(context).as("缺引擎类 + 配置在场：上下文照常启动（探测器只警不碍）").hasNotFailed();
                    assertThat(context).as("探测器激活并产出结果 bean（07 §5.5）")
                            .hasSingleBean(ToolboxAuthFailOpenDetector.FailOpenWarning.class);
                    assertThat(context.getBean(ToolboxAuthFailOpenDetector.FailOpenWarning.class).presentKeys())
                            .as("发现的键入账").contains("toolbox.auth.keycloak.server-url");
                    assertThat(appender.list)
                            .as("fail-open WARN 恰为单条（singleElement 强制——终审滚存 #2：anySatisfy 只钉至少一条）")
                            .singleElement()
                            .satisfies(event -> {
                                assertThat(event.getLevel()).as("WARN 级别").isEqualTo(Level.WARN);
                                assertThat(event.getFormattedMessage())
                                        .as("含裸奔警示与行动指引（README 警示段呼应）")
                                        .contains("fail-open")
                                        .contains("toolbox.auth.keycloak.server-url")
                                        .contains("spring-boot-starter-oauth2-resource-server")
                                        .contains("toolbox.auth.enabled=false");
                            });
                });
    }

    @Test
    void detectsEnvVarFormKeys() {
        // ③ 终审加固钉（B+C Minor #1）：SystemEnvironmentPropertySource 呈现的是裸大写下划线名
        // （如 k8s 注入），presentAuthKeys 的 TOOLBOX_AUTH_ 前缀半分支此前无覆盖——若被删本钉变红。
        // runner 属性源只能造点分格式，经 initializer 头插 MapPropertySource 模拟环境变量源。
        missingEngineRunner()
                .withInitializer(ctx -> ctx.getEnvironment().getPropertySources().addFirst(
                        new MapPropertySource("fakeSystemEnv",
                                Map.of("TOOLBOX_AUTH_KEYCLOAK_SERVER_URL", "https://kc.example.com"))))
                .run(context -> {
                    assertThat(context).as("env-var 形态同样激活探测").hasNotFailed();
                    assertThat(context.getBean(ToolboxAuthFailOpenDetector.FailOpenWarning.class).presentKeys())
                            .as("裸大写环境变量名入账（TOOLBOX_AUTH_ 前缀分支）")
                            .contains("TOOLBOX_AUTH_KEYCLOAK_SERVER_URL");
                    assertThat(appender.list).as("WARN 照发且恰单条").singleElement()
                            .satisfies(event -> assertThat(event.getFormattedMessage())
                                    .as("键名进 WARN").contains("TOOLBOX_AUTH_KEYCLOAK_SERVER_URL"));
                });
    }

    @Test
    void warnsEvenUnderGlobalLazyInitialization() {
        // ⑦ 终审加固钉（B+C Minor #3）：全局 spring.main.lazy-initialization=true 时无人注入
        // FailOpenWarning，惰化即静默废掉探测器。Boot 该属性经 SpringApplication 挂
        // LazyInitializationBeanFactoryPostProcessor 生效；runner 不走 SpringApplication，
        // 故手挂同一后处理器复现。该后处理器对已显式设 lazyInit 的定义跳过——@Lazy(false)
        // 正是借此免疫。⚠️ 本测试故意不 getBean/hasSingleBean：取 bean 会强制实例化使断言
        // 空过，唯一有效观察面是"refresh 完成即已 WARN"。
        missingEngineRunner()
                .withPropertyValues("toolbox.auth.keycloak.server-url=https://kc.example.com")
                .withInitializer(ctx -> ctx.addBeanFactoryPostProcessor(
                        new LazyInitializationBeanFactoryPostProcessor()))
                .run(context -> {
                    assertThat(context).as("全局惰化下上下文照常启动").hasNotFailed();
                    assertThat(appender.list)
                            .as("@Lazy(false) 使探测器免于惰化：refresh 即 WARN（删除该注解本钉变红）")
                            .singleElement()
                            .satisfies(event -> assertThat(event.getLevel())
                                    .as("WARN 级别").isEqualTo(Level.WARN));
                });
    }

    @Test
    void silentWhenExplicitlyDisabled() {
        missingEngineRunner()
                .withPropertyValues("toolbox.auth.enabled=false",
                        "toolbox.auth.keycloak.server-url=https://kc.example.com")
                .run(context -> {
                    assertThat(context).as("R7：enabled=false 显式退出，探测器整体不装配")
                            .doesNotHaveBean(ToolboxAuthFailOpenDetector.FailOpenWarning.class);
                    assertThat(appender.list).as("无 WARN").isEmpty();
                });
    }

    @Test
    void silentWhenNoAuthKeysPresent() {
        missingEngineRunner().run(context -> {
            assertThat(context).as("探测器装配（enabled 缺省 matchIfMissing）")
                    .hasSingleBean(ToolboxAuthFailOpenDetector.FailOpenWarning.class);
            assertThat(context.getBean(ToolboxAuthFailOpenDetector.FailOpenWarning.class).presentKeys())
                    .as("无 toolbox.auth.* 键").isEmpty();
            assertThat(appender.list).as("无使用意图不骚扰（R7）").isEmpty();
        });
    }

    @Test
    void backsOffWhenEngineClassPresent() {
        new WebApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(ToolboxAuthFailOpenDetector.class))
                .withPropertyValues("toolbox.auth.keycloak.server-url=https://kc.example.com")
                .run(context -> assertThat(context)
                        .as("引擎类在场（L1 成立）⇒ 探测器反相条件不成立，整体退让")
                        .doesNotHaveBean(ToolboxAuthFailOpenDetector.FailOpenWarning.class));
    }

    @Test
    void backsOffOutsideServletWeb() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(ToolboxAuthFailOpenDetector.class))
                .withClassLoader(new FilteredClassLoader(JwtDecoder.class))
                .withPropertyValues("toolbox.auth.keycloak.server-url=https://kc.example.com")
                .run(context -> assertThat(context)
                        .as("非 servlet web 无裸奔面，不警（07 §5.5）")
                        .doesNotHaveBean(ToolboxAuthFailOpenDetector.FailOpenWarning.class));
    }
}
