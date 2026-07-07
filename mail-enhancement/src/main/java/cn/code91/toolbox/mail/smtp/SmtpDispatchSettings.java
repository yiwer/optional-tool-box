package cn.code91.toolbox.mail.smtp;

import java.time.Duration;
import java.util.Objects;

/**
 * {@link SmtpMailDispatcher} 的派发参数（账号维度）。
 *
 * @param accountName        逻辑账号名（回执与限速 key 使用）
 * @param from               账号默认发件人；null 时发送期从 {@code MailMessage.from()} 显式取，
 *                           两处皆缺返回 {@code Err(ConfigError)}（裁定 D）
 * @param displayName        发件人显示名；null/空则仅用纯地址
 * @param rateLimitPerMinute 账号级每分钟发送上限；{@code <= 0} 不限速（裁定 C）
 * @param retryMaxAttempts   总尝试次数上限（含首次）；{@code <= 0} 回退默认 3（裁定 C）
 * @param retryBackoff       首次重试退避基值，之后逐次倍增；null 或非正回退默认 2s（裁定 C）
 */
public record SmtpDispatchSettings(String accountName, String from, String displayName,
                                   int rateLimitPerMinute, int retryMaxAttempts, Duration retryBackoff) {

    private static final Duration DEFAULT_BACKOFF = Duration.ofSeconds(2);

    public SmtpDispatchSettings {
        Objects.requireNonNull(accountName, "accountName 不得为 null");
        if (retryMaxAttempts <= 0) {
            retryMaxAttempts = 3;
        }
        if (retryBackoff == null || retryBackoff.isNegative() || retryBackoff.isZero()) {
            retryBackoff = DEFAULT_BACKOFF;
        }
    }
}
