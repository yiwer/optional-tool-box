package cn.code91.toolbox.storage.guard;

import cn.code91.facility.result.Result;
import cn.code91.toolbox.storage.core.StorageError;

/**
 * 上传守卫 Seam（L4，默认实现串联多项检查）。<b>不自动内嵌</b>在 {@code ObjectStore.put} 里
 * （put 也服务于内部生成文件），而是提供给上传入口显式调用。
 */
public interface StorageGuard {

    /**
     * 校验上传候选，任何一项违规即返回 {@code Err(ValidationError)}，消息说明被拦原因。
     */
    Result<Void, StorageError> check(UploadCandidate candidate);
}
