package cn.code91.toolbox.auth.jwt;

import org.springframework.security.oauth2.jwt.BadJwtException;

/**
 * JWKS 取键失败（KC 不可达/响应异常）的观测型异常（07 §4.3/§5.4，裁定 R8）。
 * 继承 {@link BadJwtException} 使资源服务器异常路由不变——{@code JwtAuthenticationProvider}
 * 仍转 {@code InvalidBearerTokenException} 走 401 出口（非 500 语义钉，JwksUnavailableTest）；
 * {@code AuthEntryPoint} 沿 cause 链探测本类型，把 401 description 细分为
 * {@code jwks_unavailable}（其余路径仍 {@code invalid_token}）。
 */
public class JwksUnavailableException extends BadJwtException {

    public JwksUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
