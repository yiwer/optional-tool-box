package cn.code91.toolbox.storage.core;

import java.time.Instant;

/**
 * {@code put}/{@code get} 元数据后的对象描述。
 *
 * @param key          对象 key
 * @param size         字节数
 * @param contentType  内容类型（可能为空串，供应商未返回时）
 * @param etag         供应商 ETag（可能为空串）
 * @param lastModified 最后修改时间
 */
public record ObjectMetadata(String key, long size, String contentType, String etag, Instant lastModified) {
}
