package cn.code91.toolbox.docs.exposure;

import cn.code91.toolbox.docs.core.DocsExposureGate;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 请求期过滤（06 §4.5，裁定 D）：未暴露 → 直接 404 且不落入后续 handler；暴露 → 放行。
 * gate 判定在 doFilter 时求值（非注册期缓存）——同一 filter 实例对 gate 状态变化即时生效。
 */
class DocsExposureFilterTest {

    @Test
    void blockedRequestGets404AndChainIsNotInvoked() throws Exception {
        DocsExposureFilter filter = new DocsExposureFilter(env -> false, new MockEnvironment());
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(requestFor("/v3/api-docs"), response, chain);

        assertThat(response.getStatus()).isEqualTo(404);
        assertThat(chain.getRequest()).as("未暴露时不得落入后续 filter/handler").isNull();
    }

    @Test
    void exposedRequestPassesThrough() throws Exception {
        DocsExposureFilter filter = new DocsExposureFilter(env -> true, new MockEnvironment());
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(requestFor("/v3/api-docs"), response, chain);

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(chain.getRequest()).isNotNull();
    }

    @Test
    void gateIsEvaluatedPerRequestNotCachedAtRegistrationTime() throws Exception {
        AtomicBoolean exposed = new AtomicBoolean(false);
        DocsExposureGate togglingGate = env -> exposed.get();
        DocsExposureFilter filter = new DocsExposureFilter(togglingGate, new MockEnvironment());

        MockHttpServletResponse blocked = new MockHttpServletResponse();
        filter.doFilter(requestFor("/v3/api-docs"), blocked, new MockFilterChain());
        assertThat(blocked.getStatus()).isEqualTo(404);

        exposed.set(true);
        MockHttpServletResponse passed = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(requestFor("/v3/api-docs"), passed, chain);
        assertThat(chain.getRequest()).isNotNull();
    }

    private static MockHttpServletRequest requestFor(String uri) {
        return new MockHttpServletRequest("GET", uri);
    }
}
