package cn.code91.toolbox.compare.render;

import cn.code91.facility.json.JsonUtil;
import cn.code91.facility.result.Result;
import cn.code91.toolbox.compare.core.CompareError;
import cn.code91.toolbox.compare.core.DiffResult;
import cn.code91.toolbox.compare.core.RenderError;
import cn.code91.toolbox.compare.spi.ChangeRenderer;

import java.util.Objects;

/**
 * JSON 渲染器：用 facility {@code JsonUtil} 序列化 changes 列表，适合入库审计。
 * 序列化失败经 {@link RenderError} 显式返回，不抛异常（裁定 C）。
 */
public final class JsonRenderer implements ChangeRenderer {

    @Override
    public Result<String, CompareError> render(DiffResult result) {
        Objects.requireNonNull(result, "result cannot be null");
        return JsonUtil.serialize(result.changes())
                .mapErr(wrappedError -> (CompareError) RenderError.of(
                        wrappedError.hasException() ? wrappedError.getException() : new IllegalStateException(wrappedError.getFormattedMessage())));
    }
}
