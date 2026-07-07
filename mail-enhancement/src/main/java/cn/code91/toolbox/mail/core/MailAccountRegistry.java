package cn.code91.toolbox.mail.core;

/**
 * 多账号注册表（Registry + 逻辑名模式，00 §3.3）。未配置任何账号时装配"空 Registry"兜底：
 * 应用能启动，取账号时才抛带配置引导的异常。
 */
public interface MailAccountRegistry {

    /**
     * 按逻辑账号名取派发器。
     *
     * @throws IllegalArgumentException 账号不存在时抛出，消息含 {@code toolbox.mail.accounts} 配置引导
     */
    MailDispatcher get(String accountName);

    /**
     * 默认账号（裁定 D 三级解析：显式 {@code toolbox.mail.primary} &gt; 唯一账号 &gt; {@code spring}）。
     *
     * @throws IllegalArgumentException 无法解析默认账号时抛出，消息含配置引导
     */
    MailDispatcher primary();
}
