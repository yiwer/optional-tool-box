package cn.code91.toolbox.docs.schema;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.media.IntegerSchema;
import io.swagger.v3.oas.models.media.ObjectSchema;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.media.StringSchema;
import org.springdoc.core.customizers.GlobalOpenApiCustomizer;

/**
 * 通用错误形状 schema 注册（06 §4.4 契约感知③，裁定 C-3）：向 {@code components.schemas}
 * 注册 {@code ToolboxErrorResponse}——{@code BaseResponse} 错误分支的通用形状。
 * P1 限制（06 §2.3 #2）：facility 各模块错误类型无数字 code，只能文档化通用形状，
 * 不能按 endpoint 枚举具体业务错误（P2 依赖跨模块错误码体系成熟）。
 *
 * <p>已存在同名 schema（应用自行定义）时不覆盖；实现为 {@link GlobalOpenApiCustomizer}，
 * springdoc 对默认文档与各分组文档均会应用。
 */
public class ErrorShapeOpenApiCustomizer implements GlobalOpenApiCustomizer {

    /** 通用错误形状的 schema 名，{@link ErrorShapeOperationCustomizer} 以 $ref 引用。 */
    public static final String ERROR_SCHEMA_NAME = "ToolboxErrorResponse";

    @Override
    public void customise(OpenAPI openApi) {
        if (openApi.getComponents() == null) {
            openApi.setComponents(new Components());
        }
        Components components = openApi.getComponents();
        if (components.getSchemas() != null && components.getSchemas().containsKey(ERROR_SCHEMA_NAME)) {
            return;
        }
        components.addSchemas(ERROR_SCHEMA_NAME, errorShapeSchema());
    }

    private static Schema<?> errorShapeSchema() {
        return new ObjectSchema()
                .addProperty("code", new IntegerSchema())
                .addProperty("message", new StringSchema())
                .addProperty("description", new StringSchema())
                .addProperty("data", new ObjectSchema().nullable(true))
                .description("BaseResponse 错误分支的通用形状（P1：无按 endpoint 的具体业务错误枚举）");
    }
}
