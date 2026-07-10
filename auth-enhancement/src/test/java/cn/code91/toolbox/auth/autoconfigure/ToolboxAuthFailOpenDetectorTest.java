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
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.security.oauth2.jwt.JwtDecoder;

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
                    assertThat(context).hasNotFailed();
                    assertThat(context).as("探测器激活并产出结果 bean（07 §5.5）")
                            .hasSingleBean(ToolboxAuthFailOpenDetector.FailOpenWarning.class);
                    assertThat(context.getBean(ToolboxAuthFailOpenDetector.FailOpenWarning.class).presentKeys())
                            .as("发现的键入账").contains("toolbox.auth.keycloak.server-url");
                    assertThat(appender.list)
                            .as("fail-open WARN 单条，含裸奔警示与行动指引（README 警示段呼应）")
                            .anySatisfy(event -> {
                                assertThat(event.getLevel()).isEqualTo(Level.WARN);
                                assertThat(event.getFormattedMessage())
                                        .contains("fail-open")
                                        .contains("toolbox.auth.keycloak.server-url")
                                        .contains("spring-boot-starter-oauth2-resource-server")
                                        .contains("toolbox.auth.enabled=false");
                            });
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
