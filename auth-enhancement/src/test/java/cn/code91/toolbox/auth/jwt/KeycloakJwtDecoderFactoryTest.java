package cn.code91.toolbox.auth.jwt;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.JwtDecoder;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Decoder 工厂（07 §5.4）：构建期只做本地校验与端点派生，零网络请求（JWKS 懒加载）；
 * 配置错误经 KeycloakEndpoints 抛带引导的 IllegalStateException（R5）。
 * 校验器组合（issuer/时钟偏移/可选 audience）与 JWKS 不可达 401 归一化的行为验证在
 * Task 6 WireMock 全链完成——此处只钉"能离线构建"与"fail-fast 传导"。返回类型收窄为
 * {@link JwtDecoder}（Task 6 实测修正：工厂内部委托包装需要，见生产类 Javadoc）。
 */
@DisplayName("KeycloakJwtDecoderFactory 构建")
class KeycloakJwtDecoderFactoryTest {

    @Test
    void buildsOfflineWithoutNetwork() {
        JwtDecoder decoder = KeycloakJwtDecoderFactory.create(
                "https://kc.invalid.example", "r", null, null, Duration.ofSeconds(60), List.of("RS256"));
        assertThat(decoder).as("指向不可达主机也能构建：启动零网络（R5/07 §5.4）").isNotNull();
    }

    @Test
    void issuerOverrideAndAudienceAccepted() {
        JwtDecoder decoder = KeycloakJwtDecoderFactory.create(
                "http://kc.internal:8080", "r", "https://kc.public.example/realms/r",
                "toolbox-api", Duration.ofSeconds(30), List.of("RS256", "ES256"));
        assertThat(decoder).as("issuer 覆写 + audience + 多算法组合同样离线可构建").isNotNull();
    }

    @Test
    void nullClockSkewAndAlgorithmsFallBackToDefaults() {
        // P1 账本 T3 Minor 回归钉：clockSkew=null→60s、jws-algorithms=null→RS256
        // （07 §5.4 默认值）。行为级验证（真按 60s/RS256 校验）属 WireMock 全链域，
        // 此处按本类既定范围钉"回落分支离线可构建"。
        JwtDecoder decoder = KeycloakJwtDecoderFactory.create(
                "https://kc.invalid.example", "r", null, null, null, null);
        assertThat(decoder).as("clockSkew/algorithms 双 null 回落默认值后离线可构建").isNotNull();
    }

    @Test
    void emptyAlgorithmListFallsBackToRs256() {
        JwtDecoder decoder = KeycloakJwtDecoderFactory.create(
                "https://kc.invalid.example", "r", null, null, Duration.ofSeconds(60), List.of());
        assertThat(decoder).as("空算法列表回落 RS256（07 §5.4），非 fail-fast 对象").isNotNull();
    }

    @Test
    void propagatesConfigErrors() {
        assertThatThrownBy(() -> KeycloakJwtDecoderFactory.create(
                null, "r", null, null, Duration.ofSeconds(60), List.of("RS256")))
                .as("缺 server-url 经 KeycloakEndpoints 传导 fail-fast（R5）")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("toolbox.auth.keycloak.server-url");
        assertThatThrownBy(() -> KeycloakJwtDecoderFactory.create(
                "https://kc.example.com", "r", null, null, Duration.ofSeconds(60), List.of("HS256-bogus")))
                .as("未知签名算法属真配置错误")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("jws-algorithms");
    }
}
