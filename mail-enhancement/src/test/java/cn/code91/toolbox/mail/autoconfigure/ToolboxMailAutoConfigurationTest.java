package cn.code91.toolbox.mail.autoconfigure;

import cn.code91.facility.result.Result;
import cn.code91.toolbox.mail.core.Attachment;
import cn.code91.toolbox.mail.core.MailAccountRegistry;
import cn.code91.toolbox.mail.core.MailError;
import cn.code91.toolbox.mail.core.MailMessage;
import cn.code91.toolbox.mail.core.MailReceipt;
import cn.code91.toolbox.mail.spi.AttachmentGuard;
import cn.code91.toolbox.mail.spi.MailSendListener;
import cn.code91.toolbox.mail.spi.MailTemplateRenderer;
import cn.code91.toolbox.mail.testfixtures.CapturingMailSender;
import cn.code91.toolbox.mail.testfixtures.RecordingListener;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.mail.javamail.JavaMailSender;

import java.time.Duration;
import java.util.Locale;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 装配矩阵（brief）：缺 jakarta.mail 类全不装配（FilteredClassLoader）/ enabled=false 全关 /
 * 零账号空 registry 且 get() 含配置引导 / adopt-spring-mail 收编（含 primary 三级解析各态与
 * 显式账号优先于收编）/ 用户覆盖 renderer/guard/registry 让位 / listener 多实例全回调且回调
 * 抛异常不影响发送结果 / 沙箱 fail-fast 与"误开但配了 redirect"正常 / 属性默认值绑定。
 */
class ToolboxMailAutoConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ToolboxMailAutoConfiguration.class));

    @Test
    void defaultAssemblyRegistersRendererGuardAndEmptyRegistry() {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(MailTemplateRenderer.class);
            assertThat(context).hasSingleBean(AttachmentGuard.class);
            assertThat(context).hasSingleBean(MailAccountRegistry.class);
        });
    }

    @Test
    void missingJakartaMailClassAssemblesNothing() {
        // L1（brief 矩阵行）：starter-mail 为 optional，缺 jakarta.mail 类整模块不装配，
        // 消费方应用仍可正常启动。
        contextRunner
                .withClassLoader(new FilteredClassLoader("jakarta.mail"))
                .run(context -> {
                    assertThat(context).doesNotHaveBean(MailAccountRegistry.class);
                    assertThat(context).doesNotHaveBean(MailTemplateRenderer.class);
                    assertThat(context).doesNotHaveBean(AttachmentGuard.class);
                });
    }

    @Test
    void enabledFalseAssemblesNothing() {
        contextRunner
                .withPropertyValues("toolbox.mail.enabled=false")
                .run(context -> {
                    assertThat(context).doesNotHaveBean(MailAccountRegistry.class);
                    assertThat(context).doesNotHaveBean(MailTemplateRenderer.class);
                    assertThat(context).doesNotHaveBean(AttachmentGuard.class);
                });
    }

    @Test
    void zeroAccountsAssembleEmptyRegistryWithBootstrapGuidanceOnGet() {
        contextRunner.run(context -> {
            MailAccountRegistry registry = context.getBean(MailAccountRegistry.class);

            assertThatThrownBy(() -> registry.get("notice"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("notice")
                    .hasMessageContaining("toolbox.mail.accounts")
                    .hasMessageContaining("adopt-spring-mail");
        });
    }

    @Test
    void adoptSpringMailAdoptsUserSenderAsSpringAccountAndPrimaryFallback() {
        contextRunner
                .withUserConfiguration(UserMailSenderConfig.class)
                .run(context -> {
                    MailAccountRegistry registry = context.getBean(MailAccountRegistry.class);
                    // 收编账号可按名取用；零自有账号时它就是 primary（三级解析末级同时也是唯一账号）。
                    assertThat(registry.get("spring")).isNotNull();
                    assertThat(registry.primary()).isSameAs(registry.get("spring"));
                });
    }

    @Test
    void adoptedSpringAccountSendsThroughUserSenderTakingFromFromMessage() {
        // 裁定 D：收编账号无 from 配置——MailMessage 显式给 from 即可发送，且确实走消费方的 sender。
        contextRunner
                .withUserConfiguration(UserMailSenderConfig.class)
                .run(context -> {
                    CapturingMailSender sender = context.getBean(CapturingMailSender.class);
                    MailAccountRegistry registry = context.getBean(MailAccountRegistry.class);

                    Result<MailReceipt, MailError> result = registry.get("spring").send(MailMessage.builder()
                            .from("legacy@code91.cn").to("user@customer.com").subject("s").text("t").build());

                    assertThat(result.isOk()).isTrue();
                    assertThat(result.get().account()).isEqualTo("spring");
                    assertThat(sender.sent).hasSize(1);
                });
    }

    @Test
    void adoptedSpringAccountWithoutAnyFromReturnsConfigError() {
        contextRunner
                .withUserConfiguration(UserMailSenderConfig.class)
                .run(context -> {
                    MailAccountRegistry registry = context.getBean(MailAccountRegistry.class);

                    Result<MailReceipt, MailError> result = registry.get("spring").send(
                            MailMessage.builder().to("user@customer.com").text("t").build());

                    assertThat(result.isErr()).isTrue();
                    assertThat(result.getErr()).isInstanceOf(MailError.ConfigError.class);
                });
    }

    @Test
    void adoptSpringMailFalseIgnoresUserSender() {
        contextRunner
                .withUserConfiguration(UserMailSenderConfig.class)
                .withPropertyValues("toolbox.mail.adopt-spring-mail=false")
                .run(context -> {
                    MailAccountRegistry registry = context.getBean(MailAccountRegistry.class);

                    assertThatThrownBy(() -> registry.get("spring"))
                            .isInstanceOf(IllegalArgumentException.class);
                });
    }

    @Test
    void explicitPrimaryWinsOverUniqueAndSpring() {
        contextRunner
                .withUserConfiguration(UserMailSenderConfig.class)
                .withPropertyValues(
                        "toolbox.mail.primary=alert",
                        "toolbox.mail.accounts.notice.host=localhost",
                        "toolbox.mail.accounts.alert.host=localhost")
                .run(context -> {
                    MailAccountRegistry registry = context.getBean(MailAccountRegistry.class);
                    assertThat(registry.primary()).isSameAs(registry.get("alert"));
                });
    }

    @Test
    void singleConfiguredAccountIsPrimaryWithoutExplicitConfig() {
        contextRunner
                .withPropertyValues("toolbox.mail.accounts.notice.host=localhost")
                .run(context -> {
                    MailAccountRegistry registry = context.getBean(MailAccountRegistry.class);
                    assertThat(registry.primary()).isSameAs(registry.get("notice"));
                });
    }

    @Test
    void springAccountIsPrimaryFallbackWhenAccountsAreNotUnique() {
        // 三级解析末级：自有账号 + 收编账号并存（非唯一）且未显式指定 → spring 兜底。
        contextRunner
                .withUserConfiguration(UserMailSenderConfig.class)
                .withPropertyValues("toolbox.mail.accounts.notice.host=localhost")
                .run(context -> {
                    MailAccountRegistry registry = context.getBean(MailAccountRegistry.class);
                    assertThat(registry.primary()).isSameAs(registry.get("spring"));
                });
    }

    @Test
    void explicitlyConfiguredSpringAccountWinsOverAdoption() {
        // 消费方显式声明名为 spring 的账号时，显式配置优先，收编让位（不覆盖用户意图）。
        // 显式 spring 账号指向无监听端口 → 发送必然 ConnectError；若被收编 sender 抢占则会
        // 直接捕获成功——以行为区分二者。
        contextRunner
                .withUserConfiguration(UserMailSenderConfig.class)
                .withPropertyValues(
                        "toolbox.mail.accounts.spring.host=localhost",
                        "toolbox.mail.accounts.spring.port=1",
                        "toolbox.mail.accounts.spring.from=cfg@code91.cn",
                        "toolbox.mail.accounts.spring.retry.max-attempts=1")
                .run(context -> {
                    CapturingMailSender sender = context.getBean(CapturingMailSender.class);
                    MailAccountRegistry registry = context.getBean(MailAccountRegistry.class);

                    Result<MailReceipt, MailError> result = registry.get("spring").send(
                            MailMessage.builder().to("user@customer.com").text("t").build());

                    assertThat(result.isErr()).isTrue();
                    assertThat(result.getErr()).isInstanceOf(MailError.ConnectError.class);
                    assertThat(sender.sent).isEmpty();
                });
    }

    @Test
    void accountMissingHostFailsContextStartup() {
        contextRunner
                .withPropertyValues("toolbox.mail.accounts.bad.username=someone")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .rootCause()
                            .isInstanceOf(IllegalStateException.class)
                            .hasMessageContaining("toolbox.mail.accounts.bad.host");
                });
    }

    @Test
    void sandboxEnabledWithoutRedirectOrWhitelistFailsStartupWithGuidance() {
        // 裁定 B：启动期 fail-fast，防"以为拦了其实全放行"。
        contextRunner
                .withPropertyValues("toolbox.mail.sandbox.enabled=true")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .rootCause()
                            .isInstanceOf(IllegalStateException.class)
                            .hasMessageContaining("toolbox.mail.sandbox.redirect-to")
                            .hasMessageContaining("toolbox.mail.sandbox.allowed-domains")
                            .hasMessageContaining("toolbox.mail.sandbox.allowed-recipients");
                });
    }

    @Test
    void sandboxEnabledWithRedirectStartsNormally() {
        // "生产误开沙箱但配了 redirect"路径：装配正常（发出的邮件被改道而非失败，端到端见 GreenMail 测试）。
        contextRunner
                .withPropertyValues(
                        "toolbox.mail.sandbox.enabled=true",
                        "toolbox.mail.sandbox.redirect-to=qa-inbox@code91.cn")
                .run(context -> assertThat(context).hasSingleBean(MailAccountRegistry.class));
    }

    @Test
    void sandboxAppliesToAdoptedSpringAccount() {
        // 04 §5.3 的价值主张：老项目零迁移接入沙箱——收编账号同样受沙箱约束。
        contextRunner
                .withUserConfiguration(UserMailSenderConfig.class)
                .withPropertyValues(
                        "toolbox.mail.sandbox.enabled=true",
                        "toolbox.mail.sandbox.allowed-domains=code91.cn")
                .run(context -> {
                    MailAccountRegistry registry = context.getBean(MailAccountRegistry.class);

                    Result<MailReceipt, MailError> result = registry.get("spring").send(MailMessage.builder()
                            .from("legacy@code91.cn").to("real-user@customer.com").text("t").build());

                    assertThat(result.isErr()).isTrue();
                    assertThat(result.getErr()).isInstanceOf(MailError.SandboxBlocked.class);
                });
    }

    @Test
    void allListenersNotifiedAndThrowingListenerDoesNotAffectSendResult() {
        contextRunner
                .withUserConfiguration(UserMailSenderConfig.class, ListenersConfig.class)
                .run(context -> {
                    MailAccountRegistry registry = context.getBean(MailAccountRegistry.class);

                    Result<MailReceipt, MailError> result = registry.get("spring").send(MailMessage.builder()
                            .from("legacy@code91.cn").to("user@customer.com").text("t").build());

                    assertThat(result.isOk()).as("回调异常不得影响发送结果").isTrue();
                    RecordingListener recording = context.getBean(RecordingListener.class);
                    assertThat(recording.successes).as("异常 listener 之后的 listener 仍须被回调").hasSize(1);
                });
    }

    @Test
    void userDefinedRendererGuardAndRegistryTakePrecedence() {
        contextRunner
                .withUserConfiguration(UserSeamOverridesConfig.class)
                .run(context -> {
                    assertThat(context.getBean(MailTemplateRenderer.class))
                            .isInstanceOf(UserSeamOverridesConfig.StubRenderer.class);
                    assertThat(context.getBean(AttachmentGuard.class))
                            .isInstanceOf(UserSeamOverridesConfig.StubGuard.class);
                    assertThat(context.getBean(MailAccountRegistry.class))
                            .isInstanceOf(UserSeamOverridesConfig.StubRegistry.class);
                });
    }

    @Test
    void unconfiguredPropertiesBindToDocumentedDefaults() {
        contextRunner
                .withPropertyValues("toolbox.mail.accounts.notice.host=localhost")
                .run(context -> {
                    ToolboxMailProperties properties = context.getBean(ToolboxMailProperties.class);

                    assertThat(properties.adoptSpringMail()).as("adopt-spring-mail 默认 true（裁定 D）").isTrue();
                    assertThat(properties.attachment().verifyMime()).as("verify-mime 默认 false（裁定 E）").isFalse();
                    assertThat(properties.attachment().maxSize()).isNull();
                    assertThat(properties.sandbox().enabled()).as("沙箱默认关（00 §3.2 默认值哲学）").isFalse();
                    ToolboxMailProperties.Account account = properties.accounts().get("notice");
                    assertThat(account.retry().maxAttempts()).as("retry.max-attempts 默认 3（裁定 C）").isEqualTo(3);
                    assertThat(account.retry().backoff()).as("retry.backoff 默认 2s（裁定 C）").isEqualTo(Duration.ofSeconds(2));
                    assertThat(account.rateLimitPerMinute()).as("0=不限速").isZero();
                });
    }

    @Configuration(proxyBeanMethods = false)
    static class UserMailSenderConfig {

        @Bean
        CapturingMailSender userJavaMailSender() {
            return new CapturingMailSender();
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class ListenersConfig {

        @Bean
        MailSendListener throwingListener() {
            return new MailSendListener() {
                @Override
                public void onSuccess(MailMessage message, MailReceipt receipt) {
                    throw new IllegalStateException("listener boom");
                }
            };
        }

        @Bean
        RecordingListener recordingListener() {
            return new RecordingListener();
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class UserSeamOverridesConfig {

        @Bean
        MailTemplateRenderer mailTemplateRenderer() {
            return new StubRenderer();
        }

        @Bean
        AttachmentGuard attachmentGuard() {
            return new StubGuard();
        }

        @Bean
        MailAccountRegistry mailAccountRegistry() {
            return new StubRegistry();
        }

        static final class StubRenderer implements MailTemplateRenderer {
            @Override
            public String name() {
                return "stub";
            }

            @Override
            public Result<String, MailError> render(String templateName, Map<String, Object> vars, Locale locale) {
                return Result.ok("stub");
            }
        }

        static final class StubGuard implements AttachmentGuard {
            @Override
            public Result<Void, MailError> check(Attachment attachment) {
                return Result.ok();
            }
        }

        static final class StubRegistry implements MailAccountRegistry {
            @Override
            public cn.code91.toolbox.mail.core.MailDispatcher get(String accountName) {
                throw new UnsupportedOperationException();
            }

            @Override
            public cn.code91.toolbox.mail.core.MailDispatcher primary() {
                throw new UnsupportedOperationException();
            }
        }
    }
}
