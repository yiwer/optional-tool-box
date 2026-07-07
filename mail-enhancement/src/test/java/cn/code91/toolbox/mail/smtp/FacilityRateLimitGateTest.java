package cn.code91.toolbox.mail.smtp;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link FacilityRateLimitGate}：facility 门面的降级语义钉住——容器中无 {@code RateLimiter}
 * bean（本测试环境即如此）时一律放行（限流不可用不阻断业务），gate 返回 null。
 * 命中限速的分支经派发器单测的假 gate 演练（真实令牌桶命中依赖 facility bean，属消费方装配）。
 */
class FacilityRateLimitGateTest {

    @Test
    void degradesToAllowWhenNoRateLimiterBeanIsPresent() {
        FacilityRateLimitGate gate = new FacilityRateLimitGate();

        Duration wait = gate.acquireOrSuggestWait("notice", 60);

        assertThat(wait).as("无 RateLimiter bean 时 facility 门面降级放行").isNull();
    }
}
