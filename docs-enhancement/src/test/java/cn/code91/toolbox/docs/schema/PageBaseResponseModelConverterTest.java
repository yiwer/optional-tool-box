package cn.code91.toolbox.docs.schema;

import cn.code91.facility.web.response.BaseResponse;
import cn.code91.facility.web.response.PageBaseResponse;
import com.fasterxml.jackson.databind.type.TypeFactory;
import io.swagger.v3.core.converter.AnnotatedType;
import io.swagger.v3.core.converter.ModelConverters;
import io.swagger.v3.oas.models.media.Schema;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code PageBaseResponse<X>} schema 命名修饰（裁定 C-2）：{@code {X}PageResponse}。
 */
class PageBaseResponseModelConverterTest {

    private static ModelConverters newConverters() {
        ModelConverters converters = new ModelConverters();
        converters.addConverter(new PageBaseResponseModelConverter());
        return converters;
    }

    @Test
    void decoratesParameterizedPageBaseResponseSchemaName() {
        Map<String, Schema> schemas = newConverters().readAll(annotated(
                TypeFactory.defaultInstance().constructParametricType(PageBaseResponse.class, TestDto.class)));

        assertThat(schemas).containsKey("TestDtoPageResponse");
        assertThat(schemas).doesNotContainKey("PageBaseResponseTestDto");
        // 分页字段与继承来的响应壳字段均在（结构不改）。
        assertThat(schemas.get("TestDtoPageResponse").getProperties())
                .containsKeys("code", "message", "data", "description", "total", "pageNum", "pageSize");
    }

    @Test
    void rawPageBaseResponseKeepsSwaggerDefaultName() {
        Map<String, Schema> schemas = newConverters().readAll(annotated(
                TypeFactory.defaultInstance().constructType(PageBaseResponse.class)));

        assertThat(schemas).containsKey("PageBaseResponse");
    }

    @Test
    void doesNotTouchPlainBaseResponse() {
        Map<String, Schema> schemas = newConverters().readAll(annotated(
                TypeFactory.defaultInstance().constructParametricType(BaseResponse.class, TestDto.class)));

        assertThat(schemas).doesNotContainKey("TestDtoPageResponse");
        assertThat(schemas).doesNotContainKey("TestDtoResponse");
    }

    private static AnnotatedType annotated(java.lang.reflect.Type type) {
        return new AnnotatedType(type).resolveAsRef(false);
    }

    record TestDto(String name) {
    }
}
