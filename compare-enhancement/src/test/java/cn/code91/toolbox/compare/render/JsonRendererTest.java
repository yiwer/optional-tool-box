package cn.code91.toolbox.compare.render;

import cn.code91.facility.json.JsonUtil;
import cn.code91.facility.result.Result;
import cn.code91.toolbox.compare.core.ChangeKind;
import cn.code91.toolbox.compare.core.CompareError;
import cn.code91.toolbox.compare.core.DiffResult;
import cn.code91.toolbox.compare.core.FieldChange;
import cn.code91.toolbox.compare.core.RenderError;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * JsonRenderer：facility JsonUtil 序列化 changes 列表，输出可反序列化回等价 changes；
 * 序列化失败（裁定 C）转 {@link RenderError}，不抛异常。
 */
class JsonRendererTest {

    private final JsonRenderer renderer = new JsonRenderer();

    @Test
    void serializesChangesAndCanBeDeserializedBackToEquivalentList() {
        DiffResult diff = new DiffResult(List.of(
                new FieldChange("amount", "订单金额", ChangeKind.MODIFIED, "100", "120", "100", "120"),
                new FieldChange("items[1]", "items[1]", ChangeKind.ADDED, null, "新商品", null, "新商品")
        ));

        Result<String, CompareError> result = renderer.render(diff);

        assertThat(result.isOk()).isTrue();
        String json = result.get();

        Result<List<Map<String, Object>>, ?> roundTrip = JsonUtil.deserializeToList(json, (Class<Map<String, Object>>) (Class<?>) Map.class);
        assertThat(roundTrip.isOk()).isTrue();
        List<Map<String, Object>> parsed = roundTrip.get();
        assertThat(parsed).hasSize(2);
        assertThat(parsed.get(0)).containsEntry("path", "amount").containsEntry("label", "订单金额");
        assertThat(parsed.get(1)).containsEntry("kind", "ADDED");
    }

    @Test
    void emptyChangesRendersEmptyJsonArray() {
        Result<String, CompareError> result = renderer.render(new DiffResult(List.of()));

        assertThat(result.isOk()).isTrue();
        assertThat(result.get()).isEqualTo("[]");
    }

    @Test
    void serializationFailureIsMappedToRenderError() {
        DiffResult diff = new DiffResult(List.of(
                new FieldChange("broken", "坏字段", ChangeKind.MODIFIED,
                        new UnserializableValue(), new UnserializableValue(), "old", "new")
        ));

        Result<String, CompareError> result = renderer.render(diff);

        assertThat(result.isErr()).isTrue();
        assertThat(result.getErr()).isInstanceOf(RenderError.class);
    }

    /**
     * 自引用对象，Jackson 默认配置下序列化会抛出 StackOverflow/JsonMappingException，
     * 用于逼出 {@link JsonRenderer} 的 RenderError 分支。
     */
    static final class UnserializableValue {
        @SuppressWarnings("unused")
        private UnserializableValue self = this;

        public UnserializableValue getSelf() {
            return self;
        }
    }
}
