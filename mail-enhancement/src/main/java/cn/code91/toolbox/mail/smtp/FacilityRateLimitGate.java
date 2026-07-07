package cn.code91.toolbox.mail.smtp;

import cn.code91.facility.ratelimit.RateLimitResult;
import cn.code91.facility.ratelimit.RateLimiterUtil;

import java.time.Duration;

/**
 * 默认限速门：账号级令牌桶经 facility {@code RateLimiterUtil}（裁定 C，禁止重造限流器）。
 *
 * <p><b>换算与突发语义</b>（裁定 C 要求 Javadoc 写明，实现者裁量）：
 * {@code permitsPerSecond = rate-limit-per-minute / 60.0}（匀速填充），
 * {@code capacity = rate-limit-per-minute}——桶满时允许一次性突发发出<b>整分钟配额</b>
 * （如 60/分钟的账号闲置 1 分钟后可瞬间连发 60 封），长期平均速率仍严格 ≤ 每分钟配额；
 * 该语义与"服务商按分钟窗口计数"的常见频控最接近。桶容量与速率仅在该账号首次建桶时生效
 * （facility 桶注册表语义），运行期改配置需重启。</p>
 *
 * <p>命中限速时把 facility 的 {@code retryAfterMillis} 换算为建议等待返回，<b>不阻塞等待</b>；
 * 容器中无 {@code RateLimiter} bean 时 facility 门面降级放行（限流不可用不阻断业务）。</p>
 */
final class FacilityRateLimitGate implements SmtpMailDispatcher.RateLimitGate {

    private static final String KEY_PREFIX = "toolbox.mail.send:";

    @Override
    public Duration acquireOrSuggestWait(String accountName, int permitsPerMinute) {
        RateLimitResult result = RateLimiterUtil.acquire(
                KEY_PREFIX + accountName, 1, permitsPerMinute, permitsPerMinute / 60.0);
        if (result.allowed()) {
            return null;
        }
        return Duration.ofMillis(Math.max(1L, result.retryAfterMillis()));
    }
}
