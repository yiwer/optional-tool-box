package cn.code91.toolbox.mail.core;

import java.time.Duration;
import java.util.List;

/**
 * 邮件发送错误类型，穷尽 9 种子类型（04 §4.2），调用方可 pattern matching 完全处理。
 * jakarta.mail / Spring Mail 的异常一律在 smtp adapter 内收敛为本类型，不得从公开 API 外抛。
 */
public sealed interface MailError {

    /**
     * 错误描述信息（面向开发者的中文描述，P1 不做 i18n）。
     */
    String message();

    /**
     * 账号/发件人等配置缺失或非法（启动期尽量拦，运行期兜底，如 spring 收编账号缺 from）。
     */
    record ConfigError(String message) implements MailError {
    }

    /**
     * SMTP 认证失败（535 等），重试无意义，直返不重试。
     */
    record AuthError(String message) implements MailError {
    }

    /**
     * 连接/握手/超时类失败——唯一可重试的错误类型（裁定 C）。
     *
     * @param cause 原始异常（可为 null），保留供排查
     */
    record ConnectError(String message, Throwable cause) implements MailError {
    }

    /**
     * 收件人非法或被服务器拒绝，直返不重试。
     *
     * @param invalidAddresses 无效地址列表（服务器未逐个报出时为空列表）
     */
    record InvalidRecipient(String message, List<String> invalidAddresses) implements MailError {

        public InvalidRecipient {
            invalidAddresses = invalidAddresses == null ? List.of() : List.copyOf(invalidAddresses);
        }
    }

    /**
     * 模板缺失或渲染失败（消息含模板 classpath 路径）。
     */
    record TemplateError(String message) implements MailError {
    }

    /**
     * 附件守卫拦截（危险扩展名/超大小/黑名单/MIME 不符），消息说明被拦原因。
     */
    record AttachmentRejected(String message) implements MailError {
    }

    /**
     * 本地限速命中（裁定 C：不阻塞等待，直接返回）。
     *
     * @param suggestedWait 建议等待时长后重试
     */
    record RateLimited(String message, Duration suggestedWait) implements MailError {
    }

    /**
     * 沙箱拦截（非白名单收件人），携带原收件人便于排查（04 §4.2）。
     *
     * @param originalRecipients 本次发送的全部原收件人（to+cc+bcc）
     */
    record SandboxBlocked(String message, List<String> originalRecipients) implements MailError {

        public SandboxBlocked {
            originalRecipients = originalRecipients == null ? List.of() : List.copyOf(originalRecipients);
        }
    }

    /**
     * 未归入其余类型的提供方/底层异常兜底，保留原始异常便于排查。
     *
     * @param cause 原始异常（可为 null）
     */
    record ProviderError(String message, Throwable cause) implements MailError {
    }
}
