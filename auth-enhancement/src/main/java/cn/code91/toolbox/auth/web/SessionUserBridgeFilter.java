package cn.code91.toolbox.auth.web;

import cn.code91.facility.web.session.SessionUserHolder;
import cn.code91.toolbox.auth.core.AuthContext;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;

import java.io.IOException;

/**
 * facility {@code SessionUserHolder} 桥接（07 §4.3，R3 opt-in，默认关）：认证完成后把
 * {@link cn.code91.toolbox.auth.core.CurrentUser} 填入 ThreadLocal，兼容存量
 * {@code SessionUserHolder.getUser(...)} 习惯代码；finally 必清（与 facility
 * {@code SessionUserClearInterceptor} 双清无害）。实现为普通 {@link Filter}（非
 * OncePerRequestFilter——那需要 spring-web，07 §4.3 依赖面约束）；set/clear 幂等，
 * forward/error 二次进入无副作用。public：消费方自建 chain（Seam 1）可自行挂载。
 */
public class SessionUserBridgeFilter implements Filter {

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        AuthContext.current().ifPresent(SessionUserHolder::setUser);
        try {
            chain.doFilter(request, response);
        } finally {
            SessionUserHolder.clear();
        }
    }
}
