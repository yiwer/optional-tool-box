package cn.code91.toolbox.docs.schema;

import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;
import org.springdoc.core.customizers.OperationCustomizer;
import org.springframework.web.method.HandlerMethod;

/**
 * default 错误响应追加（裁定 C-3）：对尚无 {@code default} 响应的 operation 追加指向
 * {@link ErrorShapeOpenApiCustomizer#ERROR_SCHEMA_NAME} 的 {@code default} 响应；
 * 已有 {@code default} 的 operation 不动（尊重应用显式注解声明）。
 */
public class ErrorShapeOperationCustomizer implements OperationCustomizer {

    @Override
    public Operation customize(Operation operation, HandlerMethod handlerMethod) {
        ApiResponses responses = operation.getResponses();
        if (responses == null) {
            responses = new ApiResponses();
            operation.setResponses(responses);
        }
        if (responses.containsKey(ApiResponses.DEFAULT)) {
            return operation;
        }
        responses.addApiResponse(ApiResponses.DEFAULT, new ApiResponse()
                .description("统一错误响应（BaseResponse 错误分支通用形状）")
                .content(new Content().addMediaType(org.springframework.http.MediaType.APPLICATION_JSON_VALUE,
                        new MediaType().schema(new Schema<>()
                                .$ref("#/components/schemas/" + ErrorShapeOpenApiCustomizer.ERROR_SCHEMA_NAME)))));
        return operation;
    }
}
