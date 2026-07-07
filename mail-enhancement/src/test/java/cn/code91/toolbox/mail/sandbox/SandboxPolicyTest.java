package cn.code91.toolbox.mail.sandbox;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 沙箱矩阵（裁定 B）：redirect / 白名单命中 / 未命中拦截 / 双未配 fail-fast / enabled=false 直通；
 * redirect 优先于白名单；部分未命中即整体拦截（fail-closed）。
 */
class SandboxPolicyTest {

    @Test
    void enabledWithoutAnyInterceptionMeansFailsFastAtConstruction() {
        // 裁定 B：两者皆空的沙箱等于纯放行，必须启动期拒绝；消息含配置路径与两种配置方式。
        assertThatThrownBy(() -> new SandboxPolicy(true, null, null, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("toolbox.mail.sandbox.redirect-to")
                .hasMessageContaining("toolbox.mail.sandbox.allowed-domains")
                .hasMessageContaining("toolbox.mail.sandbox.allowed-recipients");
    }

    @Test
    void enabledWithBlankRedirectAndEmptyListsAlsoFailsFast() {
        assertThatThrownBy(() -> new SandboxPolicy(true, "   ", List.of(), List.of()))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void disabledSandboxNeverRequiresConfigurationAndPassesEverything() {
        assertThatCode(() -> new SandboxPolicy(false, null, null, null)).doesNotThrowAnyException();

        SandboxDecision decision = SandboxPolicy.disabled().decide(List.of("anyone@example.com"));

        assertThat(decision).isInstanceOf(SandboxDecision.Pass.class);
    }

    @Test
    void redirectReplacesAllRecipientsAndCarriesOriginals() {
        SandboxPolicy policy = new SandboxPolicy(true, "qa-inbox@code91.cn", null, null);

        SandboxDecision decision = policy.decide(List.of("real-user@customer.com", "boss@customer.com"));

        assertThat(decision).isInstanceOf(SandboxDecision.Redirect.class);
        SandboxDecision.Redirect redirect = (SandboxDecision.Redirect) decision;
        assertThat(redirect.redirectTo()).isEqualTo("qa-inbox@code91.cn");
        assertThat(redirect.originalRecipients())
                .containsExactly("real-user@customer.com", "boss@customer.com");
    }

    @Test
    void redirectTakesPrecedenceOverWhitelist() {
        // 04 §4.3 决策顺序：redirect-to 优先——即便收件人命中白名单也整体重定向。
        SandboxPolicy policy = new SandboxPolicy(
                true, "qa-inbox@code91.cn", List.of("code91.cn"), List.of("dev@code91.cn"));

        SandboxDecision decision = policy.decide(List.of("dev@code91.cn"));

        assertThat(decision).isInstanceOf(SandboxDecision.Redirect.class);
    }

    @Test
    void allowedDomainHitPassesCaseInsensitively() {
        SandboxPolicy policy = new SandboxPolicy(true, null, List.of("Code91.CN"), null);

        SandboxDecision decision = policy.decide(List.of("dev@code91.cn", "qa@CODE91.cn"));

        assertThat(decision).isInstanceOf(SandboxDecision.Pass.class);
    }

    @Test
    void allowedRecipientHitPassesCaseInsensitively() {
        SandboxPolicy policy = new SandboxPolicy(true, null, null, List.of("QA@code91.cn"));

        SandboxDecision decision = policy.decide(List.of("qa@Code91.cn"));

        assertThat(decision).isInstanceOf(SandboxDecision.Pass.class);
    }

    @Test
    void anyMissedRecipientBlocksWholeSendWithOriginalsAndOffenders() {
        // 部分未命中即整体拦截（fail-closed）：originalRecipients 携带全部原收件人，
        // disallowed 精确指出违规地址。
        SandboxPolicy policy = new SandboxPolicy(true, null, List.of("code91.cn"), null);

        SandboxDecision decision = policy.decide(List.of("dev@code91.cn", "real-user@customer.com"));

        assertThat(decision).isInstanceOf(SandboxDecision.Blocked.class);
        SandboxDecision.Blocked blocked = (SandboxDecision.Blocked) decision;
        assertThat(blocked.originalRecipients())
                .containsExactly("dev@code91.cn", "real-user@customer.com");
        assertThat(blocked.disallowed()).containsExactly("real-user@customer.com");
    }

    @Test
    void recipientWithoutDomainPartIsBlockedByDomainWhitelist() {
        SandboxPolicy policy = new SandboxPolicy(true, null, List.of("code91.cn"), null);

        SandboxDecision decision = policy.decide(List.of("not-an-email"));

        assertThat(decision).isInstanceOf(SandboxDecision.Blocked.class);
    }

    @Test
    void domainAndRecipientWhitelistsCombineAsUnion() {
        SandboxPolicy policy = new SandboxPolicy(
                true, null, List.of("code91.cn"), List.of("partner@customer.com"));

        SandboxDecision decision = policy.decide(List.of("dev@code91.cn", "partner@customer.com"));

        assertThat(decision).isInstanceOf(SandboxDecision.Pass.class);
    }
}
