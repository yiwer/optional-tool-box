package cn.code91.toolbox.mail.sandbox;

import java.util.List;

/**
 * 沙箱决策逻辑（裁定 B，独立可测）。构造期校验（fail-fast）：{@code enabled=true} 且
 * {@code redirect-to} 与 {@code allowed-domains}/{@code allowed-recipients} 均未配置时抛
 * {@link IllegalStateException}——两者皆空的沙箱等于纯放行，"以为拦了其实全放行"比不拦更危险。
 */
public final class SandboxPolicy {

    private final boolean enabled;
    private final String redirectTo;
    private final List<String> allowedDomains;
    private final List<String> allowedRecipients;

    /**
     * @throws IllegalStateException enabled=true 且未配置任何拦截手段时抛出，
     *                               消息含配置路径与两种配置方式（裁定 B）
     */
    public SandboxPolicy(boolean enabled, String redirectTo,
                         List<String> allowedDomains, List<String> allowedRecipients) {
        this.enabled = enabled;
        this.redirectTo = hasText(redirectTo) ? redirectTo.trim() : null;
        this.allowedDomains = allowedDomains == null ? List.of() : List.copyOf(allowedDomains);
        this.allowedRecipients = allowedRecipients == null ? List.of() : List.copyOf(allowedRecipients);
        if (enabled && this.redirectTo == null && this.allowedDomains.isEmpty() && this.allowedRecipients.isEmpty()) {
            throw new IllegalStateException(
                    "toolbox.mail.sandbox.enabled=true 但既未配置 toolbox.mail.sandbox.redirect-to（方式一：全部收件人"
                            + "整体重定向到测试邮箱），也未配置 toolbox.mail.sandbox.allowed-domains / "
                            + "toolbox.mail.sandbox.allowed-recipients（方式二：白名单放行）——这样的沙箱等于纯放行，"
                            + "拒绝启动以防误发真实用户；请二选一配置，或关闭 toolbox.mail.sandbox.enabled");
        }
    }

    /**
     * 关闭态沙箱（一切直通）。
     */
    public static SandboxPolicy disabled() {
        return new SandboxPolicy(false, null, null, null);
    }

    public boolean enabled() {
        return enabled;
    }

    /**
     * 对一次发送的全部收件人（to+cc+bcc 合并）做裁决。决策顺序（04 §4.3）：
     * redirect-to 优先（即便命中白名单也重定向）；其次白名单，<b>全部</b>命中才放行，
     * 任一未命中即整体拦截（fail-closed，部分放行会把未命中的地址漏出去）。
     */
    public SandboxDecision decide(List<String> recipients) {
        if (!enabled) {
            return new SandboxDecision.Pass();
        }
        if (redirectTo != null) {
            return new SandboxDecision.Redirect(redirectTo, recipients);
        }
        List<String> disallowed = recipients.stream().filter(r -> !isAllowed(r)).toList();
        if (disallowed.isEmpty()) {
            return new SandboxDecision.Pass();
        }
        return new SandboxDecision.Blocked(recipients, disallowed);
    }

    /**
     * 白名单为并集语义：整地址命中 allowed-recipients，或 {@code @} 后域名命中 allowed-domains，
     * 皆为命中；两类匹配均大小写不敏感。
     */
    private boolean isAllowed(String recipient) {
        for (String allowed : allowedRecipients) {
            if (allowed.equalsIgnoreCase(recipient)) {
                return true;
            }
        }
        String domain = domainOf(recipient);
        if (domain == null) {
            return false;
        }
        for (String allowed : allowedDomains) {
            if (allowed.equalsIgnoreCase(domain)) {
                return true;
            }
        }
        return false;
    }

    private static String domainOf(String recipient) {
        int at = recipient.lastIndexOf('@');
        if (at < 0 || at == recipient.length() - 1) {
            return null;
        }
        return recipient.substring(at + 1);
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
