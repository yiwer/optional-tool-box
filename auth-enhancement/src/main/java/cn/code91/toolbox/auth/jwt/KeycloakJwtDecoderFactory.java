package cn.code91.toolbox.auth.jwt;

import cn.code91.facility.log.LogUtil;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * JwtDecoder 离线构建（07 §5.4）：JWKS 地址本地派生（{@link KeycloakEndpoints}），构建期
 * <b>零网络请求</b>——Nimbus 首个 bearer 请求才取 JWKS（内置缓存 + 未知 kid 自动刷新），
 * KC 暂时不可达不压垮服务启动。校验器：issuer（可 override，内外网地址不一致场景）+
 * 时钟偏移 + 可选 audience（KC 默认 aud 不含 API client-id，须 KC 侧配 mapper 后才开启，
 * 07 §2.2 坑 2）。返回类型收窄为 {@link JwtDecoder}（原 {@code NimbusJwtDecoder}）：
 * {@link #normalizeDecodeFailures} 需要包一层委托，{@code NimbusJwtDecoder} 是 final 类
 * 无法子类化。
 */
public final class KeycloakJwtDecoderFactory {

    private KeycloakJwtDecoderFactory() {
    }

    public static JwtDecoder create(String serverUrl, String realm, String issuerUriOverride,
                                    String requiredAudience, Duration clockSkew,
                                    List<String> jwsAlgorithms) {
        String jwksUri = KeycloakEndpoints.jwksUri(serverUrl, realm);
        String issuer = issuerUriOverride == null || issuerUriOverride.isBlank()
                ? KeycloakEndpoints.issuer(serverUrl, realm) : issuerUriOverride.trim();

        NimbusJwtDecoder delegate = NimbusJwtDecoder.withJwkSetUri(jwksUri)
                .jwsAlgorithms(algs -> resolveAlgorithms(jwsAlgorithms).forEach(algs::add))
                .build();

        List<OAuth2TokenValidator<Jwt>> validators = new ArrayList<>();
        validators.add(new JwtTimestampValidator(clockSkew == null ? Duration.ofSeconds(60) : clockSkew));
        validators.add(new JwtIssuerValidator(issuer));
        if (requiredAudience != null && !requiredAudience.isBlank()) {
            String aud = requiredAudience.trim();
            validators.add(new JwtClaimValidator<List<String>>(JwtClaimNames.AUD,
                    audList -> audList != null && audList.contains(aud)));
        }
        delegate.setJwtValidator(new DelegatingOAuth2TokenValidator<>(validators));
        return normalizeDecodeFailures(delegate);
    }

    /**
     * JWKS 取用异常统一转 {@link BadJwtException}（07 §5.4 语义钉，Task 6 WireMock 全链实测
     * 修正）：{@code NimbusJwtDecoder} 对签名/时间戳/claim 校验失败已抛 {@code BadJwtException}
     * （资源服务器 {@code JwtAuthenticationProvider} 转 {@code InvalidBearerTokenException} ⇒
     * 401 invalid_token），但 JWKS 端点不可达（网络异常/超时，经 Nimbus
     * {@code RemoteKeySourceException}）只抛泛化 {@link JwtException}——该分支被
     * {@code JwtAuthenticationProvider} 转的是 {@code AuthenticationServiceException}，未被
     * {@code BearerTokenAuthenticationFilter} 捕获，作为未处理异常向上抛（容器兜底通常 500）。
     * IdP 暂时不可达是运行期常态而非服务端 bug，不得放大为 500——此处把"非 Bad 的 JwtException"
     * 升格为 {@link JwksUnavailableException}（BadJwtException 子类，复用既有 401 出口，且被
     * {@code AuthEntryPoint} 沿 cause 链探测细分为 {@code jwks_unavailable} 观测码，07 §4.3/R8）
     * 并打 WARN 单条（R8 运维可见性）；非 JwtException 家族的异常（真正的内部错误）不受影响，
     * 仍按原样向上抛。
     */
    private static JwtDecoder normalizeDecodeFailures(NimbusJwtDecoder delegate) {
        return token -> {
            try {
                return delegate.decode(token);
            } catch (BadJwtException alreadyBad) {
                throw alreadyBad;
            } catch (JwtException generic) {
                LogUtil.warn("auth-enhancement JWKS 取键失败（KC 不可达或响应异常），"
                        + "本次请求按 401/jwks_unavailable 处理：{}", generic.getMessage());
                throw new JwksUnavailableException(generic.getMessage(), generic);
            }
        };
    }

    private static List<SignatureAlgorithm> resolveAlgorithms(List<String> names) {
        List<String> effective = names == null || names.isEmpty() ? List.of("RS256") : names;
        List<SignatureAlgorithm> result = new ArrayList<>();
        for (String name : effective) {
            SignatureAlgorithm alg = SignatureAlgorithm.from(name == null ? null : name.trim());
            if (alg == null) {
                throw new IllegalStateException(
                        "toolbox.auth.jwt.jws-algorithms 含未识别算法：\"" + name
                                + "\"（合法值如 RS256/ES256，须与 Keycloak realm 签名算法一致）");
            }
            result.add(alg);
        }
        return result;
    }
}
