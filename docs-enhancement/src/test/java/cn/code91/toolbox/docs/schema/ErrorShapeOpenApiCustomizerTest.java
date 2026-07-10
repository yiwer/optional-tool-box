package cn.code91.toolbox.docs.schema;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.media.StringSchema;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 通用错误形状 schema 注册（06 §4.4 契约感知③，裁定 C-3）：向 components.schemas 注册
 * ToolboxErrorResponse（code int32 / message string / description string / data 可空 object）。
 * P1 限制（06 §2.3 #2）：facility 错误类型无数字 code，只能文档化通用形状，不能按 endpoint 枚举。
 */
class ErrorShapeOpenApiCustomizerTest {

    private final ErrorShapeOpenApiCustomizer customizer = new ErrorShapeOpenApiCustomizer();

    @Test
    void registersToolboxErrorResponseSchemaWithGenericErrorShape() {
        OpenAPI openApi = new OpenAPI();

        customizer.customise(openApi);

        Schema<?> schema = openApi.getComponents().getSchemas().get("ToolboxErrorResponse");
        assertThat(schema).isNotNull();
        assertThat(schema.getType()).isEqualTo("object");

        Schema<?> code = (Schema<?>) schema.getProperties().get("code");
        assertThat(code.getType()).isEqualTo("integer");
        assertThat(code.getFormat()).isEqualTo("int32");
        assertThat(((Schema<?>) schema.getProperties().get("message")).getType()).isEqualTo("string");
        assertThat(((Schema<?>) schema.getProperties().get("description")).getType()).isEqualTo("string");

        Schema<?> data = (Schema<?>) schema.getProperties().get("data");
        assertThat(data.getType()).isEqualTo("object");
        assertThat(data.getNullable()).isTrue();
    }

    @Test
    void preservesExistingComponentSchemas() {
        OpenAPI openApi = new OpenAPI().components(new Components().addSchemas("Existing", new StringSchema()));

        customizer.customise(openApi);

        assertThat(openApi.getComponents().getSchemas()).containsKeys("Existing", "ToolboxErrorResponse");
    }

    @Test
    void doesNotOverwriteApplicationDefinedSchemaOfSameName() {
        Schema<?> applicationOwned = new StringSchema();
        OpenAPI openApi = new OpenAPI()
                .components(new Components().addSchemas("ToolboxErrorResponse", applicationOwned));

        customizer.customise(openApi);

        assertThat(openApi.getComponents().getSchemas().get("ToolboxErrorResponse")).isSameAs(applicationOwned);
    }
}
