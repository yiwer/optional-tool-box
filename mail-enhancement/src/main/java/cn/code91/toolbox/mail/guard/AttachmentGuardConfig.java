package cn.code91.toolbox.mail.guard;

import java.util.Set;

/**
 * {@link DefaultAttachmentGuard} 的检查参数。guard 包不得依赖 autoconfigure（ArchUnit 约束），
 * 故不直接使用 {@code ToolboxMailProperties}，由装配层转换后传入（同 storage {@code GuardConfig}）。
 *
 * @param maxSize           允许的最大字节数（{@code <= 0} 视为不限制）
 * @param blockedExtensions 配置叠加的扩展名黑名单（与 facility 内置危险扩展名并集生效），
 *                          大小写不敏感，不含点号
 * @param verifyMime        是否启用魔数嗅探与声明 Content-Type 比对（默认 false，裁定 E）
 */
public record AttachmentGuardConfig(long maxSize, Set<String> blockedExtensions, boolean verifyMime) {

    public AttachmentGuardConfig {
        blockedExtensions = blockedExtensions == null ? Set.of() : Set.copyOf(blockedExtensions);
    }
}
