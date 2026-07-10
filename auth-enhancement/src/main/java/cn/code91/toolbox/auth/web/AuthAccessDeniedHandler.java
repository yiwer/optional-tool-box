package cn.code91.toolbox.auth.web;

import cn.code91.facility.log.LogUtil;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;

import java.io.IOException;

/**
 * 403 出口（07 §4.3）：已认证但权限不足。RFC 6750 对应错误码固定
 * {@code insufficient_scope}；响应体形态同 {@link AuthEntryPoint}。
 */
public class AuthAccessDeniedHandler implements AccessDeniedHandler {

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response,
                       AccessDeniedException accessDeniedException) throws IOException {
        LogUtil.debug("auth-enhancement 403：uri={}", request.getRequestURI());
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setHeader("WWW-Authenticate", "Bearer error=\"insufficient_scope\"");
        AuthEntryPoint.writeJson(response, HttpServletResponse.SC_FORBIDDEN,
                "toolbox.auth.forbidden", "Access denied", "insufficient_scope");
    }
}
