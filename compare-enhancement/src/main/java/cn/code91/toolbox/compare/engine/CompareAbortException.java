package cn.code91.toolbox.compare.engine;

import cn.code91.toolbox.compare.core.CompareError;

/**
 * 内部短路信号：遍历过程中遇到结构性错误（深度超限/环/类型不一致/字段读取失败）时抛出，
 * 由 {@link ReflectionDiffEngine} 顶层捕获并转换为 {@link cn.code91.facility.result.Result#err}。
 * 不对外暴露，仅用于避免在递归签名上处处透传 {@code Result}。
 */
final class CompareAbortException extends RuntimeException {

    private final CompareError error;

    CompareAbortException(CompareError error) {
        super(error.message(), null, false, false);
        this.error = error;
    }

    CompareError error() {
        return error;
    }
}
