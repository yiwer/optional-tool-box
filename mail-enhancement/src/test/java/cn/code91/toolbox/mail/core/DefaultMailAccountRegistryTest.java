package cn.code91.toolbox.mail.core;

import cn.code91.facility.result.Result;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link DefaultMailAccountRegistry}：按逻辑账号名查找 + primary 三级解析（裁定 D），
 * 零账号/未知账号抛带 {@code toolbox.mail} 配置引导的异常（同 storage 空 registry 模式）。
 */
class DefaultMailAccountRegistryTest {

    private static final MailDispatcher NOTICE = new StubDispatcher();
    private static final MailDispatcher ALERT = new StubDispatcher();
    private static final MailDispatcher SPRING = new StubDispatcher();

    @Test
    void getReturnsDispatcherForKnownAccount() {
        DefaultMailAccountRegistry registry =
                new DefaultMailAccountRegistry(Map.of("notice", NOTICE), null);

        assertThat(registry.get("notice")).isSameAs(NOTICE);
    }

    @Test
    void getUnknownAccountThrowsWithConfigGuidanceListingAvailable() {
        DefaultMailAccountRegistry registry =
                new DefaultMailAccountRegistry(Map.of("notice", NOTICE), null);

        assertThatThrownBy(() -> registry.get("alert"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("alert")
                .hasMessageContaining("toolbox.mail.accounts.alert")
                .hasMessageContaining("notice");
    }

    @Test
    void emptyRegistryGetThrowsWithBootstrapGuidance() {
        // 零账号兜底（裁定 D）：get() 消息须同时给出两种起步方式——声明 accounts 或收编 spring.mail。
        DefaultMailAccountRegistry registry = new DefaultMailAccountRegistry(Map.of(), null);

        assertThatThrownBy(() -> registry.get("notice"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("notice")
                .hasMessageContaining("toolbox.mail.accounts")
                .hasMessageContaining("adopt-spring-mail");
    }

    @Test
    void emptyRegistryPrimaryThrowsWithBootstrapGuidance() {
        DefaultMailAccountRegistry registry = new DefaultMailAccountRegistry(Map.of(), null);

        assertThatThrownBy(registry::primary)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("toolbox.mail.accounts");
    }

    @Test
    void explicitPrimaryWinsOverEverything() {
        DefaultMailAccountRegistry registry = new DefaultMailAccountRegistry(
                Map.of("notice", NOTICE, "alert", ALERT, "spring", SPRING), "alert");

        assertThat(registry.primary()).isSameAs(ALERT);
    }

    @Test
    void explicitPrimaryPointingToMissingAccountThrowsWithGuidance() {
        DefaultMailAccountRegistry registry =
                new DefaultMailAccountRegistry(Map.of("notice", NOTICE), "gone");

        assertThatThrownBy(registry::primary)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("toolbox.mail.primary")
                .hasMessageContaining("gone");
    }

    @Test
    void singleAccountIsPrimaryWithoutExplicitConfig() {
        DefaultMailAccountRegistry registry =
                new DefaultMailAccountRegistry(Map.of("notice", NOTICE), null);

        assertThat(registry.primary()).isSameAs(NOTICE);
    }

    @Test
    void springAccountIsPrimaryFallbackWhenMultipleAccountsExist() {
        // 三级解析第三级：非唯一账号且未显式指定 → spring 收编账号兜底。
        DefaultMailAccountRegistry registry = new DefaultMailAccountRegistry(
                Map.of("notice", NOTICE, "spring", SPRING), null);

        assertThat(registry.primary()).isSameAs(SPRING);
    }

    @Test
    void multipleAccountsWithoutExplicitPrimaryOrSpringThrowsWithGuidance() {
        DefaultMailAccountRegistry registry = new DefaultMailAccountRegistry(
                Map.of("notice", NOTICE, "alert", ALERT), null);

        assertThatThrownBy(registry::primary)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("toolbox.mail.primary");
    }

    /**
     * 最小桩实现，仅用于验证 registry 查找语义。
     */
    private static final class StubDispatcher implements MailDispatcher {

        @Override
        public Result<MailReceipt, MailError> send(MailMessage message) {
            throw new UnsupportedOperationException();
        }

        @Override
        public CompletableFuture<Result<MailReceipt, MailError>> sendAsync(MailMessage message) {
            throw new UnsupportedOperationException();
        }
    }
}
