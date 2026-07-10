package cn.code91.toolbox.auth.integration;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * 测试铸 token（07 §9 无 Docker 主矩阵）：进程内 RSA 密钥对，公钥经 WireMock 假 JWKS
 * 暴露，私钥签发可控 claim 的 JWT——坏签名用第二把独立密钥。
 */
final class TestTokens {

    static final RSAKey KEY;
    static final RSAKey ROGUE_KEY;   // 坏签名场景：不在 JWKS 中的密钥

    static {
        try {
            KEY = new RSAKeyGenerator(2048).keyID("it-key").generate();
            ROGUE_KEY = new RSAKeyGenerator(2048).keyID("rogue-key").generate();
        } catch (Exception e) {
            throw new IllegalStateException("测试密钥生成失败", e);
        }
    }

    private TestTokens() {
    }

    /** {@return 仅含公钥的 JWKS JSON}（WireMock 响应体） */
    static String jwksJson() {
        return new JWKSet(KEY.toPublicJWK()).toString();
    }

    /** 铸标准 KC 形态 token；customizer 可改任意 claim（过期/错 issuer/错 aud 等场景） */
    static String mint(String issuer, Consumer<JWTClaimsSet.Builder> customizer) {
        return mintWith(KEY, issuer, customizer);
    }

    /** 用不在 JWKS 的密钥签发（坏签名场景） */
    static String mintRogue(String issuer) {
        return mintWith(ROGUE_KEY, issuer, b -> { });
    }

    private static String mintWith(RSAKey key, String issuer, Consumer<JWTClaimsSet.Builder> customizer) {
        try {
            JWTClaimsSet.Builder builder = new JWTClaimsSet.Builder()
                    .subject("2f0c3f9a-0000-0000-0000-000000000001")
                    .issuer(issuer)
                    .audience(List.of("toolbox-api"))
                    .issueTime(Date.from(Instant.now().minusSeconds(5)))
                    .expirationTime(Date.from(Instant.now().plusSeconds(300)))
                    .claim("preferred_username", "alice")
                    .claim("email", "alice@example.com")
                    .claim("realm_access", Map.of("roles", List.of("order-admin")))
                    .claim("resource_access", Map.of("toolbox-api", Map.of("roles", List.of("doc-reader"))))
                    .claim("scope", "openid profile");
            customizer.accept(builder);
            SignedJWT jwt = new SignedJWT(
                    new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.getKeyID()).build(),
                    builder.build());
            jwt.sign(new RSASSASigner(key));
            return jwt.serialize();
        } catch (Exception e) {
            throw new IllegalStateException("测试 token 签发失败", e);
        }
    }
}
