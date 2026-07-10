package cn.code91.toolbox.auth.web;

import cn.code91.facility.json.JsonUtil;
import cn.code91.facility.locale.LocaleUtil;
import cn.code91.facility.log.LogUtil;
import cn.code91.facility.web.response.BaseResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;

import java.io.IOException;

/**
 * 401 出口（07 §4.3）：Security Filter 层异常绕过 MVC 全局异常处理器，此处直接写
 * facility {@link BaseResponse} JSON 对齐全仓响应形态；同时保留 RFC 6750
 * {@code WWW-Authenticate} 头不破标准客户端。description 只带 OAuth2 错误码
 * （invalid_token 等），异常内因不回显（防探测）。
 */
public class AuthEntryPoint implements AuthenticationEntryPoint {

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response,
                         AuthenticationException authException) throws IOException {
        String errorCode = authException instanceof OAuth2AuthenticationException oauth
                ? oauth.getError().getErrorCode() : null;
        LogUtil.debug("auth-enhancement 401：uri={}，error={}", request.getRequestURI(), errorCode);

        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setHeader("WWW-Authenticate",
                errorCode == null ? "Bearer" : "Bearer error=\"" + errorCode + "\"");
        writeJson(response, HttpServletResponse.SC_UNAUTHORIZED,
                "toolbox.auth.unauthorized", "Authentication required or token invalid", errorCode);
    }

    /** 401/403 共用的 BaseResponse JSON 写出（package-private 供 {@link AuthAccessDeniedHandler} 复用） */
    static void writeJson(HttpServletResponse response, int code,
                          String messageKey, String fallback, String description) throws IOException {
        response.setContentType("application/json;charset=UTF-8");
        response.setCharacterEncoding("UTF-8");
        BaseResponse<Void> body = BaseResponse.err(code,
                LocaleUtil.translateMessageWithFallback(messageKey, null, fallback, LocaleUtil.getLocale()));
        body.setDescription(description);
        response.getWriter().write(JsonUtil.serializeUnsafe(body));
    }
}
