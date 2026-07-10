package cn.code91.toolbox.docs.core;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * core 契约的编译期钉子（06 §4.1/§4.2）：sealed 穷尽 switch 不写 default 分支——若后续
 * 有人往 {@link DocsError} 加回 {@code InvalidGroupConfig}/{@code SchemaResolutionFailed}
 * 死桩变体（全局约束 3 明令禁止），本测试直接编译失败暴露。
 */
class DocsErrorTest {

    @Test
    void sealedSwitchIsExhaustiveWithSingleExportFailedVariant() {
        DocsError error = new ExportFailed("导出失败原因");

        // 无 default 分支：编译器强制穷尽 permits 集，P1 集合 = {ExportFailed}。
        String described = switch (error) {
            case ExportFailed(String message) -> message;
        };

        assertThat(described).isEqualTo("导出失败原因");
    }

    @Test
    void exportFailedMessageAccessorSatisfiesDocsErrorContract() {
        ExportFailed failed = new ExportFailed("unknown group");

        assertThat(failed.message()).isEqualTo("unknown group");
        assertThat(((DocsError) failed).message()).isEqualTo("unknown group");
    }

    @Test
    void exportFormatEnumeratesExactlyThreeFormats() {
        assertThat(ExportFormat.values())
                .containsExactly(ExportFormat.JSON, ExportFormat.YAML, ExportFormat.POSTMAN);
    }
}
