package cn.code91.toolbox.mail.core;

import java.util.Map;

/**
 * {@link MailAccountRegistry} 默认实现：不可变 map 查找。未知账号名抛
 * {@link IllegalArgumentException}，消息含 {@code toolbox.mail.accounts} 配置引导
 * （00 §3.3 空 Registry 兜底，同 storage 空 registry 模式）。
 * primary 解析（裁定 D）：显式 {@code toolbox.mail.primary} &gt; 唯一账号 &gt; {@code spring}。
 */
public final class DefaultMailAccountRegistry implements MailAccountRegistry {

    /**
     * adopt-spring-mail 收编账号的固定逻辑名（裁定 D）。
     */
    public static final String SPRING_ACCOUNT = "spring";

    private final Map<String, MailDispatcher> dispatchers;
    private final String explicitPrimary;

    /**
     * @param dispatchers     逻辑账号名 → 派发器；零账号时传空 map
     * @param explicitPrimary {@code toolbox.mail.primary} 显式配置值，未配置为 null
     */
    public DefaultMailAccountRegistry(Map<String, MailDispatcher> dispatchers, String explicitPrimary) {
        this.dispatchers = Map.copyOf(dispatchers);
        this.explicitPrimary = explicitPrimary;
    }

    @Override
    public MailDispatcher get(String accountName) {
        MailDispatcher dispatcher = dispatchers.get(accountName);
        if (dispatcher == null) {
            throw new IllegalArgumentException(guidanceForUnknown(accountName));
        }
        return dispatcher;
    }

    @Override
    public MailDispatcher primary() {
        if (explicitPrimary != null && !explicitPrimary.isBlank()) {
            MailDispatcher dispatcher = dispatchers.get(explicitPrimary);
            if (dispatcher == null) {
                throw new IllegalArgumentException(
                        "toolbox.mail.primary=\"" + explicitPrimary + "\" 指向不存在的账号（当前已配置账号："
                                + dispatchers.keySet() + "）；请改为其中之一，或在 toolbox.mail.accounts."
                                + explicitPrimary + " 下补齐该账号");
            }
            return dispatcher;
        }
        if (dispatchers.size() == 1) {
            return dispatchers.values().iterator().next();
        }
        MailDispatcher spring = dispatchers.get(SPRING_ACCOUNT);
        if (spring != null) {
            return spring;
        }
        if (dispatchers.isEmpty()) {
            throw new IllegalArgumentException(bootstrapGuidance("（默认账号）"));
        }
        throw new IllegalArgumentException(
                "存在多个邮件账号（" + dispatchers.keySet() + "）且未指定默认账号，无法解析 primary()；"
                        + "请配置 toolbox.mail.primary 指定其中之一");
    }

    private String guidanceForUnknown(String accountName) {
        if (dispatchers.isEmpty()) {
            return bootstrapGuidance("\"" + accountName + "\"");
        }
        return "未找到邮件账号 \"" + accountName + "\"：请在 toolbox.mail.accounts." + accountName
                + " 下声明该 SMTP 账号（host/username/password…）；当前已配置账号：" + dispatchers.keySet();
    }

    /**
     * 零账号兜底引导（裁定 D）：同时给出两种起步方式——自建 accounts，或引入 starter-mail
     * 走 spring.mail.* + adopt-spring-mail 收编。
     */
    private static String bootstrapGuidance(String requested) {
        return "未找到邮件账号 " + requested + "：当前未配置任何邮件账号。请在 toolbox.mail.accounts.<账号名> 下"
                + "声明 SMTP 账号（host/username/password…）；或引入 spring-boot-starter-mail 并配置 "
                + "spring.mail.*（toolbox.mail.adopt-spring-mail 默认开启，会将其收编为名为 \""
                + SPRING_ACCOUNT + "\" 的账号）";
    }
}
