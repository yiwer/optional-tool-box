package cn.code91.toolbox.mail.core;

import java.util.Objects;

/**
 * 邮件附件（P1 为内存字节，不做流式，裁定 E；超大附件由守卫的 max-size 兜底）。
 *
 * <p><b>字节数组不做防御性拷贝</b>：附件可达数十 MB，逐层拷贝内存翻倍；调用方在
 * {@code send} 返回前不得修改传入的数组（与 {@code InputStream} 类似的所有权约定）。</p>
 *
 * @param filename    原始文件名（未清洗，交由 {@link cn.code91.toolbox.mail.spi.AttachmentGuard} 校验）
 * @param contentType 声明的 Content-Type；为 null 时由邮件引擎按文件名推断
 * @param bytes       附件内容
 */
public record Attachment(String filename, String contentType, byte[] bytes) {

    public Attachment {
        Objects.requireNonNull(filename, "filename 不得为 null");
        Objects.requireNonNull(bytes, "bytes 不得为 null");
    }

    /**
     * 不声明 Content-Type 的便捷工厂（由邮件引擎按文件名推断）。
     */
    public static Attachment of(String filename, byte[] bytes) {
        return new Attachment(filename, null, bytes);
    }

    /**
     * 声明 Content-Type 的便捷工厂（verify-mime=true 时守卫会与魔数嗅探结果比对）。
     */
    public static Attachment of(String filename, String contentType, byte[] bytes) {
        return new Attachment(filename, contentType, bytes);
    }
}
