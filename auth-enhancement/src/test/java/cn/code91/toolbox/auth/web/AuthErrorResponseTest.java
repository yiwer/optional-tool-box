package cn.code91.toolbox.auth.web;

import cn.code91.facility.web.response.BaseResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.InsufficientAuthenticationException;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;

import cn.code91.facility.json.JsonUtil;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 401/403 响应形态（07 §4.3）：facility BaseResponse JSON + RFC 6750 WWW-Authenticate 头；
 * description 只携带安全无害的 OAuth2 错误码，不回显 token 内容与校验内因（防探测）。
 */
@DisplayName("AuthEntryPoint / AuthAccessDeniedHandler 响应形态")
class AuthErrorResponseTest {

    @Test
    void unauthorizedWithOAuth2ErrorCode() throws Exception {
        var response = new MockHttpServletResponse();
        new AuthEntryPoint().commence(new MockHttpServletRequest(), response,
                new OAuth2AuthenticationException(new OAuth2Error("invalid_token", "过期细节不外泄", null)));

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getHeader("WWW-Authenticate"))
                .as("RFC 6750 头保留标准错误码").isEqualTo("Bearer error=\"invalid_token\"");
        assertThat(response.getContentType()).startsWith("application/json");
        BaseResponse<?> body = JsonUtil.deserialize(response.getContentAsString(), BaseResponse.class)
                .get();
        assertThat(body.getCode()).isEqualTo(401);
        assertThat(body.getMessage()).as("i18n 兜底消息非空").isNotBlank();
        assertThat(body.getDescription()).as("description 只带错误码").isEqualTo("invalid_token");
        assertThat(response.getContentAsString())
                .as("不回显异常内因（07 §4.3 防探测）").doesNotContain("过期细节不外泄");
    }

    @Test
    void unauthorizedWithoutBearerToken() throws Exception {
        var response = new MockHttpServletResponse();
        new AuthEntryPoint().commence(new MockHttpServletRequest(), response,
                new InsufficientAuthenticationException("anonymous"));

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getHeader("WWW-Authenticate"))
                .as("无 token 场景 RFC 6750：裸 Bearer 挑战").isEqualTo("Bearer");
        BaseResponse<?> body = JsonUtil.deserialize(response.getContentAsString(), BaseResponse.class)
                .get();
        assertThat(body.getCode()).isEqualTo(401);
        assertThat(body.getDescription()).as("非 OAuth2 异常无错误码").isNull();
    }

    @Test
    void forbiddenShape() throws Exception {
        var response = new MockHttpServletResponse();
        new AuthAccessDeniedHandler().handle(new MockHttpServletRequest(), response,
                new AccessDeniedException("拒绝内因不外泄"));

        assertThat(response.getStatus()).as("403 Forbidden 状态").isEqualTo(403);
        assertThat(response.getHeader("WWW-Authenticate"))
                .as("RFC 6750 头带 insufficient_scope 错误码").isEqualTo("Bearer error=\"insufficient_scope\"");
        BaseResponse<?> body = JsonUtil.deserialize(response.getContentAsString(), BaseResponse.class)
                .get();
        assertThat(body.getCode()).as("BaseResponse.code 与 HTTP 状态一致").isEqualTo(403);
        assertThat(body.getDescription()).as("description 只带错误码（与 401 侧同则）").isEqualTo("insufficient_scope");
        assertThat(response.getContentAsString())
                .as("403 侧同样不回显异常内因（07 §4.3 防探测，与 401 侧对称）").doesNotContain("拒绝内因不外泄");
    }
}
