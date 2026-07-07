package cn.code91.toolbox.storage.guard;

import java.io.InputStream;

/**
 * 待校验的上传候选。
 *
 * @param filename            原始文件名（未清洗，交由 {@link StorageGuard} 内部清洗校验）
 * @param declaredContentType 调用方声明的 Content-Type（用于与魔数嗅探结果比对）
 * @param size                声明的字节数（用于 max-size 校验）
 * @param peekable            内容流。verify-mime=false 时守卫不读取该流；verify-mime=true 时
 *                            要求其支持 {@code mark/reset}（如用 {@code java.io.BufferedInputStream}
 *                            包装），守卫嗅探头部字节后会 {@code reset} 复位，调用方之后仍可从
 *                            同一个流完整读到全部内容（05 §8"先 check 再 put"用法成立）；
 *                            不支持 {@code mark/reset} 的流在 verify-mime=true 下会被拒绝
 *                            （{@code Err(ValidationError)} 且消息含包装/关闭开关的引导）
 */
public record UploadCandidate(String filename, String declaredContentType, long size, InputStream peekable) {
}
