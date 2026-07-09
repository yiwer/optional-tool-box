package cn.code91.toolbox.storage.core;

import cn.code91.facility.result.Result;

import java.io.InputStream;
import java.time.Duration;
import java.util.List;

/**
 * 对象存储统一 Seam。业务只面向本接口与逻辑桶名，底下是阿里云 OSS、AWS S3（及一切 S3
 * 兼容：MinIO/腾讯 COS/华为 OBS）或本地文件系统。
 *
 * <p><b>P1 范围（控制器裁定 A）</b>：不含任何分片相关方法——05 设计文档 §4.1 的分片族
 * （{@code initiateMultipart}/{@code uploadPart}/{@code completeMultipart}/
 * {@code abortMultipart}/{@code presignedUploadPart}）随 P2 一并加入，避免三个 adapter
 * 在 P1 出现成片 {@link NotSupported} 死桩。</p>
 */
public interface ObjectStore {

    /**
     * 逻辑桶名（配置中声明的名字，非物理桶名）。
     */
    String bucketName();

    /**
     * 上传对象。{@code contentLength} 必须与 {@code data} 实际可读字节数一致
     * （各 adapter 据此声明 Content-Length，不做自行探测）。
     */
    Result<ObjectMetadata, StorageError> put(String key, InputStream data, long contentLength, String contentType);

    /**
     * 下载对象。<b>返回的 {@link InputStream} 由调用方负责关闭</b>（控制器裁定 D，P1 不提供
     * {@code getAsBytes} 便捷方法）。
     */
    Result<InputStream, StorageError> get(String key);

    /**
     * 按前缀列出对象，语义为"key 以 prefix 开头"（非目录语义，无 prefix 内的分隔符收敛）；
     * {@code maxKeys} 限制单次返回条数，不做自动翻页。
     *
     * <p><b>返回顺序未定义</b>：由各 adapter 的底层遍历/服务端行为决定（云端多为字典序，
     * local 为文件系统遍历序），调用方不得依赖任何特定排序；需要有序结果请自行排序。
     * {@code maxKeys} 截断作用于该未定义顺序——不保证是字典序意义上的"前 N 个"。</p>
     */
    Result<List<ObjectKey>, StorageError> list(String prefix, int maxKeys);

    /**
     * 删除对象；对象本不存在时是否视为成功由各 adapter 文档说明（幂等优先）。
     */
    Result<Void, StorageError> delete(String key);

    /**
     * 判断对象是否存在。
     */
    Result<Boolean, StorageError> exists(String key);

    /**
     * 签发预签名上传 URL，供前端直传（不经应用服务器）。
     */
    Result<PresignedUrl, StorageError> presignedPut(String key, Duration expires, String contentType);

    /**
     * 签发预签名下载 URL。
     */
    Result<PresignedUrl, StorageError> presignedGet(String key, Duration expires);
}
