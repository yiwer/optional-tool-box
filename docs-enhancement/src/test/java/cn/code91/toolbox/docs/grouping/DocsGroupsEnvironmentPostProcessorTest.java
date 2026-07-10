package cn.code91.toolbox.docs.grouping;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 分组翻译（06 §4.3，裁定 B）：{@code toolbox.docs.groups.<name>.*} →
 * {@code springdoc.group-configs[i].*} 属性面翻译层——不重新实现分组机制。
 *
 * <p>MockEnvironment 的自有 property source 基于 {@code java.util.Properties}（Hashtable），
 * 多组时 Binder 绑定的 map 迭代序不保证，故断言先按组名定位索引再逐键校验。
 */
class DocsGroupsEnvironmentPostProcessorTest {

    private static final String SOURCE_NAME = "toolboxDocsGroups";

    private final DocsGroupsEnvironmentPostProcessor postProcessor = new DocsGroupsEnvironmentPostProcessor();

    @Test
    void translatesEachGroupSelectorIntoIndexedSpringdocNativeProperties() {
        MockEnvironment env = new MockEnvironment();
        env.setProperty("toolbox.docs.groups.admin.packages-to-scan[0]", "com.biz.admin");
        env.setProperty("toolbox.docs.groups.admin.packages-to-scan[1]", "com.biz.admin2");
        env.setProperty("toolbox.docs.groups.admin.paths-to-exclude[0]", "/admin/internal/**");
        env.setProperty("toolbox.docs.groups.open.paths-to-match[0]", "/api/public/**");

        postProcessor.postProcessEnvironment(env, null);

        assertThat(env.getPropertySources().contains(SOURCE_NAME)).isTrue();
        int admin = indexOfGroup(env, "admin");
        int open = indexOfGroup(env, "open");
        assertThat(admin).isNotEqualTo(open);

        String adminPrefix = "springdoc.group-configs[" + admin + "].";
        assertThat(env.getProperty(adminPrefix + "group")).isEqualTo("admin");
        assertThat(env.getProperty(adminPrefix + "packages-to-scan[0]")).isEqualTo("com.biz.admin");
        assertThat(env.getProperty(adminPrefix + "packages-to-scan[1]")).isEqualTo("com.biz.admin2");
        assertThat(env.getProperty(adminPrefix + "paths-to-exclude[0]")).isEqualTo("/admin/internal/**");
        // 未配置的选择器不产出任何键（而非空串占位）。
        assertThat(env.getProperty(adminPrefix + "paths-to-match[0]")).isNull();

        String openPrefix = "springdoc.group-configs[" + open + "].";
        assertThat(env.getProperty(openPrefix + "group")).isEqualTo("open");
        assertThat(env.getProperty(openPrefix + "paths-to-match[0]")).isEqualTo("/api/public/**");
        assertThat(env.getProperty(openPrefix + "packages-to-scan[0]")).isNull();
        assertThat(env.getProperty(openPrefix + "paths-to-exclude[0]")).isNull();

        // 只有两组：不存在越界的第三个 group-configs 条目。
        assertThat(env.getProperty("springdoc.group-configs[2].group")).isNull();
    }

    @Test
    void groupWithAllThreeSelectorsEmptyFailsFastWithConfigPathGuidance() {
        MockEnvironment env = new MockEnvironment();
        // 组节点存在（有 name 键）但三个选择器全空——绑定出的 Group 三列表均为空。
        env.setProperty("toolbox.docs.groups.empty-group.packages-to-scan", "");

        assertThatThrownBy(() -> postProcessor.postProcessEnvironment(env, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("toolbox.docs.groups.empty-group")
                .hasMessageContaining("至少配置 packages-to-scan 或 paths-to-match 之一");
    }

    @Test
    void moduleDisabledSkipsTranslationEntirely() {
        MockEnvironment env = new MockEnvironment();
        env.setProperty("toolbox.docs.enabled", "false");
        env.setProperty("toolbox.docs.groups.admin.packages-to-scan[0]", "com.biz.admin");

        postProcessor.postProcessEnvironment(env, null);

        assertThat(env.getPropertySources().contains(SOURCE_NAME)).isFalse();
        assertThat(env.getProperty("springdoc.group-configs[0].group")).isNull();
    }

    @Test
    void noGroupsConfiguredAddsNoPropertySource() {
        MockEnvironment env = new MockEnvironment();

        postProcessor.postProcessEnvironment(env, null);

        assertThat(env.getPropertySources().contains(SOURCE_NAME)).isFalse();
    }

    @Test
    void addLastKeepsConsumersExplicitSpringdocNativePropertiesWinning() {
        MockEnvironment env = new MockEnvironment();
        // 消费方显式写的 springdoc 原生属性（README 声明两种配置面不要混用；混用时原生面优先）。
        env.setProperty("springdoc.group-configs[0].group", "consumer-explicit");
        env.setProperty("toolbox.docs.groups.admin.packages-to-scan[0]", "com.biz.admin");

        postProcessor.postProcessEnvironment(env, null);

        // addLast：翻译产物位于最低优先级，消费方显式值不被覆盖……
        assertThat(env.getProperty("springdoc.group-configs[0].group")).isEqualTo("consumer-explicit");
        // ……但翻译产物本身存在于自有 property source 中（被遮蔽而非未产出）。
        Object shadowed = env.getPropertySources().get(SOURCE_NAME).getProperty("springdoc.group-configs[0].group");
        assertThat(shadowed).isEqualTo("admin");
    }

    /** MockEnvironment 的 Hashtable 键序不保证，按组名反查翻译后的索引。 */
    private static int indexOfGroup(MockEnvironment env, String groupName) {
        for (int i = 0; i < 16; i++) {
            if (groupName.equals(env.getProperty("springdoc.group-configs[" + i + "].group"))) {
                return i;
            }
        }
        throw new AssertionError("翻译产物中不存在组：" + groupName);
    }
}
