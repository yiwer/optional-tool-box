package cn.code91.toolbox.auth.jwt;

import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
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
 * 07 §2.2 坑 2）。
 */
public final class KeycloakJwtDecoderFactory {

    private KeycloakJwtDecoderFactory() {
    }

    public static NimbusJwtDecoder create(String serverUrl, String realm, String issuerUriOverride,
                                          String requiredAudience, Duration clockSkew,
                                          List<String> jwsAlgorithms) {
        String jwksUri = KeycloakEndpoints.jwksUri(serverUrl, realm);
        String issuer = issuerUriOverride == null || issuerUriOverride.isBlank()
                ? KeycloakEndpoints.issuer(serverUrl, realm) : issuerUriOverride.trim();

        NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(jwksUri)
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
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(validators));
        return decoder;
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
