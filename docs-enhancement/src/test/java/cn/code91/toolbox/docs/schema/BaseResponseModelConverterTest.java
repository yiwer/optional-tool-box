package cn.code91.toolbox.docs.schema;

import cn.code91.facility.web.response.BaseResponse;
import cn.code91.facility.web.response.PageBaseResponse;
import com.fasterxml.jackson.databind.type.TypeFactory;
import io.swagger.v3.core.converter.AnnotatedType;
import io.swagger.v3.core.converter.ModelConverters;
import io.swagger.v3.oas.models.media.Schema;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code BaseResponse<X>} schema 命名修饰（06 §4.4 契约感知②，裁定 C-2）：
 * {@code {X 的 swagger 默认命名片段}Response}；raw/未参数化不动；解析失败或命名冲突
 * 退回默认命名 + 告警（不产生错误值、不抛异常）；字段结构不改。
 */
@ExtendWith(OutputCaptureExtension.class)
class BaseResponseModelConverterTest {

    private static ModelConverters newConverters() {
        ModelConverters converters = new ModelConverters();
        converters.addConverter(new BaseResponseModelConverter());
        return converters;
    }

    @Test
    void decoratesParameterizedBaseResponseSchemaName() {
        Map<String, Schema> schemas = newConverters().readAll(annotated(
                TypeFactory.defaultInstance().constructParametricType(BaseResponse.class, TestDto.class)));

        assertThat(schemas).containsKey("TestDtoResponse");
        assertThat(schemas).doesNotContainKey("BaseResponseTestDto");
        // 字段结构不改：springdoc/swagger 本就解析正确（06 §1 技术澄清），只动名字。
        assertThat(schemas.get("TestDtoResponse").getProperties())
                .containsKeys("code", "message", "data", "description");
    }

    @Test
    void listParameterizedTypeUsesSwaggerDefaultFragmentOfListType() {
        Map<String, Schema> schemas = newConverters().readAll(annotated(
                TypeFactory.defaultInstance().constructParametricType(
                        BaseResponse.class,
                        TypeFactory.defaultInstance().constructCollectionLikeType(List.class, TestDto.class))));

        assertThat(schemas).containsKey("ListTestDtoResponse");
    }

    @Test
    void rawBaseResponseKeepsSwaggerDefaultName() {
        Map<String, Schema> schemas = newConverters().readAll(annotated(
                TypeFactory.defaultInstance().constructType(BaseResponse.class)));

        assertThat(schemas).containsKey("BaseResponse");
    }

    @Test
    void doesNotHijackPageBaseResponseSubclass() {
        // raw class 精确判等：PageBaseResponse 由专职转换器处理，本转换器放行走默认命名。
        Map<String, Schema> schemas = newConverters().readAll(annotated(
                TypeFactory.defaultInstance().constructParametricType(PageBaseResponse.class, TestDto.class)));

        assertThat(schemas).doesNotContainKey("TestDtoResponse");
        assertThat(schemas).doesNotContainKey("TestDtoPageResponse");
    }

    @Test
    void conflictingDecoratedNamesFallBackToDefaultNamingWithWarning(CapturedOutput output) {
        ModelConverters converters = newConverters();

        Map<String, Schema> first = converters.readAll(annotated(
                TypeFactory.defaultInstance().constructParametricType(BaseResponse.class, ConflictA.TestDto.class)));
        Map<String, Schema> second = converters.readAll(annotated(
                TypeFactory.defaultInstance().constructParametricType(BaseResponse.class, ConflictB.TestDto.class)));

        // 先到者得名；后到的同名不同型退回 swagger 默认命名（BaseResponseTestDto）并告警。
        assertThat(first).containsKey("TestDtoResponse");
        assertThat(second).containsKey("BaseResponseTestDto");
        assertThat(output).contains("TestDtoResponse");
        assertThat(output.getOut()).containsIgnoringCase("warn");
    }

    private static AnnotatedType annotated(java.lang.reflect.Type type) {
        return new AnnotatedType(type).resolveAsRef(false);
    }

    record TestDto(String name, int amount) {
    }

    static class ConflictA {
        record TestDto(String left) {
        }
    }

    static class ConflictB {
        record TestDto(String right) {
        }
    }
}
