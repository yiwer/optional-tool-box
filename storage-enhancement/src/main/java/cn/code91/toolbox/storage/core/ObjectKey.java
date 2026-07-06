package cn.code91.toolbox.storage.core;

import java.time.Instant;

/**
 * {@link ObjectStore#list} 返回的条目，不含 contentType/etag（供应商 list 接口通常不返回，
 * 需要完整元数据应对具体 key 调用 {@link ObjectStore#get}/head 类操作）。
 *
 * @param key          对象 key
 * @param size         字节数
 * @param lastModified 最后修改时间
 */
public record ObjectKey(String key, long size, Instant lastModified) {
}
