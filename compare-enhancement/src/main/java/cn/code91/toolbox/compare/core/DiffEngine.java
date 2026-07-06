package cn.code91.toolbox.compare.core;

import cn.code91.facility.result.Result;

/**
 * 差异引擎 Seam 接口：给定新旧两个同类型对象，产出字段级变更清单。
 */
public interface DiffEngine {

    /**
     * 使用默认 {@link DiffOptions} 对比。
     */
    <T> Result<DiffResult, CompareError> diff(T oldValue, T newValue);

    /**
     * 使用指定 {@link DiffOptions} 对比。
     */
    <T> Result<DiffResult, CompareError> diff(T oldValue, T newValue, DiffOptions options);
}
