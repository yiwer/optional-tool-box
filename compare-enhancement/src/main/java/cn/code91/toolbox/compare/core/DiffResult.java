package cn.code91.toolbox.compare.core;

import cn.code91.facility.result.Result;
import cn.code91.toolbox.compare.spi.ChangeRenderer;

import java.util.List;
import java.util.Objects;

/**
 * 差异对比结果：字段级变更清单。
 *
 * @param changes 变更列表，顺序即遍历顺序
 */
public record DiffResult(List<FieldChange> changes) {

    public DiffResult {
        Objects.requireNonNull(changes, "changes cannot be null");
        changes = List.copyOf(changes);
    }

    /**
     * 是否完全一致（无任何变更）。
     */
    public boolean identical() {
        return changes.isEmpty();
    }

    /**
     * 委托渲染器渲染为展示文本，失败经 {@link CompareError} 显式返回。
     */
    public Result<String, CompareError> render(ChangeRenderer renderer) {
        Objects.requireNonNull(renderer, "renderer cannot be null");
        return renderer.render(this);
    }
}
