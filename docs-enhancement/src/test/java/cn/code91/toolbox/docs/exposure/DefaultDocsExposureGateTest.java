package cn.code91.toolbox.docs.exposure;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 环境门禁判定矩阵（06 §4.5，裁定 D）：激活 profile 与 production-profiles 有交集 且
 * expose-in-production=false（默认）→ 不暴露；其余一律暴露。
 */
class DefaultDocsExposureGateTest {

    private static final List<String> DEFAULT_PRODUCTION_PROFILES = List.of("prod", "production");

    @Test
    void productionProfileWithoutExplicitOptInIsNotExposed() {
        DefaultDocsExposureGate gate = new DefaultDocsExposureGate(DEFAULT_PRODUCTION_PROFILES, false);

        assertThat(gate.isExposed(envWithProfiles("prod"))).isFalse();
        assertThat(gate.isExposed(envWithProfiles("production"))).isFalse();
        // 交集判定：生产 profile 混在其它 profile 中同样命中。
        assertThat(gate.isExposed(envWithProfiles("metrics", "prod"))).isFalse();
    }

    @Test
    void productionProfileWithExplicitOptInIsExposed() {
        DefaultDocsExposureGate gate = new DefaultDocsExposureGate(DEFAULT_PRODUCTION_PROFILES, true);

        assertThat(gate.isExposed(envWithProfiles("prod"))).isTrue();
    }

    @Test
    void nonProductionProfileIsExposedByDefault() {
        DefaultDocsExposureGate gate = new DefaultDocsExposureGate(DEFAULT_PRODUCTION_PROFILES, false);

        assertThat(gate.isExposed(envWithProfiles())).isTrue();
        assertThat(gate.isExposed(envWithProfiles("dev"))).isTrue();
    }

    @Test
    void nonProductionProfileWithOptInStaysExposed() {
        DefaultDocsExposureGate gate = new DefaultDocsExposureGate(DEFAULT_PRODUCTION_PROFILES, true);

        assertThat(gate.isExposed(envWithProfiles("dev"))).isTrue();
    }

    @Test
    void customProductionProfilesReplaceDefaults() {
        DefaultDocsExposureGate gate = new DefaultDocsExposureGate(List.of("live"), false);

        assertThat(gate.isExposed(envWithProfiles("live"))).isFalse();
        assertThat(gate.isExposed(envWithProfiles("prod"))).isTrue();
    }

    private static MockEnvironment envWithProfiles(String... profiles) {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles(profiles);
        return env;
    }
}
