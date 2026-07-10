package cn.code91.toolbox.docs.schema;

import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * default 错误响应追加（裁定 C-3）：仅对尚无 {@code default} 响应的 operation 追加
 * {@code $ref: ToolboxErrorResponse}；已有 {@code default} 的不动（尊重应用显式注解）。
 */
class ErrorShapeOperationCustomizerTest {

    private final ErrorShapeOperationCustomizer customizer = new ErrorShapeOperationCustomizer();

    @Test
    void appendsDefaultErrorResponseWhenAbsent() {
        Operation operation = new Operation()
                .responses(new ApiResponses().addApiResponse("200", new ApiResponse().description("ok")));

        Operation customized = customizer.customize(operation, null);

        ApiResponse defaultResponse = customized.getResponses().get("default");
        assertThat(defaultResponse).isNotNull();
        assertThat(defaultResponse.getDescription()).isEqualTo("统一错误响应（BaseResponse 错误分支通用形状）");
        assertThat(defaultResponse.getContent().get("application/json").getSchema().get$ref())
                .isEqualTo("#/components/schemas/ToolboxErrorResponse");
        // 既有成功响应不动。
        assertThat(customized.getResponses().get("200").getDescription()).isEqualTo("ok");
    }

    @Test
    void respectsExistingDefaultResponse() {
        ApiResponse applicationOwned = new ApiResponse().description("应用显式声明的兜底响应");
        Operation operation = new Operation()
                .responses(new ApiResponses().addApiResponse("default", applicationOwned));

        Operation customized = customizer.customize(operation, null);

        assertThat(customized.getResponses().get("default")).isSameAs(applicationOwned);
    }

    @Test
    void createsResponsesContainerWhenMissing() {
        Operation operation = new Operation();

        Operation customized = customizer.customize(operation, null);

        assertThat(customized.getResponses().get("default")).isNotNull();
    }
}
