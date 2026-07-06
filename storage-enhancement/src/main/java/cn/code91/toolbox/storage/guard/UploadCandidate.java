package cn.code91.toolbox.storage.guard;

import java.io.InputStream;

/**
 * 待校验的上传候选。
 *
 * @param filename            原始文件名（未清洗，交由 {@link StorageGuard} 内部清洗校验）
 * @param declaredContentType 调用方声明的 Content-Type（用于与魔数嗅探结果比对）
 * @param size                声明的字节数（用于 max-size 校验）
 * @param peekable            内容流；魔数嗅探只需读取前若干字节，实现需保证该流可被
 *                            {@link StorageGuard} 探测而不影响后续消费方读取（如支持
 *                            {@code mark/reset} 的流，或调用方另行传入副本）
 */
public record UploadCandidate(String filename, String declaredContentType, long size, InputStream peekable) {
}
