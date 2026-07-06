package cn.code91.toolbox.compare.spi;

import cn.code91.facility.result.Result;
import cn.code91.toolbox.compare.core.CompareError;
import cn.code91.toolbox.compare.core.DiffResult;

/**
 * 差异结果渲染器扩展点。
 *
 * <p>渲染失败（如 JSON 序列化异常）经 {@link cn.code91.toolbox.compare.core.RenderError}
 * 显式返回，不抛异常。
 */
public interface ChangeRenderer {

    Result<String, CompareError> render(DiffResult result);
}
