package cn.code91.toolbox.mail.guard;

import cn.code91.facility.error.WrappedError;
import cn.code91.facility.mime.MimeTyping;
import cn.code91.facility.path.Filenames;
import cn.code91.facility.result.Result;
import cn.code91.toolbox.mail.core.Attachment;
import cn.code91.toolbox.mail.core.MailError;
import cn.code91.toolbox.mail.spi.AttachmentGuard;

/**
 * {@link AttachmentGuard} 默认实现。检查顺序（裁定 E，镜像 storage {@code DefaultStorageGuard}，
 * 固定不可调整，前一项失败即短路）：
 * <ol>
 *   <li>facility {@code Filenames} 清洗与危险扩展名</li>
 *   <li>{@code attachment.max-size}</li>
 *   <li>配置叠加的 {@code attachment.blocked-extensions}（与 facility 内置危险扩展名并集，而非替代）</li>
 *   <li>{@code verify-mime=true} 时 facility {@code MimeTyping} 魔数嗅探与声明 Content-Type 比对</li>
 * </ol>
 *
 * <p><b>MimeTyping/Tika 降级披露</b>：{@code MimeTyping} 的静态字段在类加载期直接
 * {@code new Tika()}，若消费方未引入 optional 的 {@code tika-core}，类加载会抛
 * {@code NoClassDefFoundError}。本类因此在真正调用 {@code MimeTyping} 之前先做一次惰性、
 * 缓存的 classpath 探测；{@code verify-mime=true} 但 Tika 不可用时选择<b>拒绝该附件</b>
 * （fail-closed，{@code Err(AttachmentRejected)} 消息提示补齐 {@code tika-core} 依赖）而非
 * 静默跳过嗅探——静默放行等于把用户显式打开的安全开关悄悄关掉（00 §2 原则 7 不静默降级）。</p>
 *
 * <p>与 storage 守卫的差异：探测缓存为<b>实例级</b>而非静态——探测所用 {@code ClassLoader}
 * 是构造参数（装配层传入容器 beanClassLoader），{@code FilteredClassLoader} 装配测试即可真实
 * 演练"缺 tika"分支，无需 storage 那样的静态 override 测试钩子。</p>
 */
public final class DefaultAttachmentGuard implements AttachmentGuard {

    private static final String TIKA_PROBE_CLASS = "org.apache.tika.Tika";

    private final AttachmentGuardConfig config;
    private final ClassLoader probeClassLoader;

    /**
     * 惰性单次探测结果缓存；{@code volatile} 保证跨线程可见（竞态下重复探测同一个只读判定，
     * 代价可忽略）。
     */
    private volatile Boolean tikaPresentCache;

    public DefaultAttachmentGuard(AttachmentGuardConfig config) {
        this(config, DefaultAttachmentGuard.class.getClassLoader());
    }

    /**
     * @param probeClassLoader Tika 探测所用类加载器（装配层传入容器 beanClassLoader，
     *                         使 {@code FilteredClassLoader} 条件测试对探测生效）
     */
    public DefaultAttachmentGuard(AttachmentGuardConfig config, ClassLoader probeClassLoader) {
        this.config = config;
        this.probeClassLoader = probeClassLoader;
    }

    @Override
    public Result<Void, MailError> check(Attachment attachment) {
        Result<String, WrappedError> sanitized = Filenames.sanitize(attachment.filename());
        if (sanitized.isErr()) {
            return Result.err(new MailError.AttachmentRejected(
                    "附件文件名非法：" + sanitized.getErr().getFormattedMessage()
                            + "（原始文件名：\"" + attachment.filename() + "\"）"));
        }
        String filename = sanitized.get();

        if (Filenames.isDangerousExtension(filename)) {
            return Result.err(new MailError.AttachmentRejected(
                    "附件 \"" + filename + "\" 使用危险扩展名（facility 内置黑名单），拒绝发送"));
        }

        if (config.maxSize() > 0 && attachment.bytes().length > config.maxSize()) {
            return Result.err(new MailError.AttachmentRejected(
                    "附件 \"" + filename + "\" 大小 " + attachment.bytes().length + " 字节超过上限 "
                            + config.maxSize() + " 字节（toolbox.mail.attachment.max-size）"));
        }

        String extension = Filenames.extension(filename);
        if (extension != null && config.blockedExtensions().stream().anyMatch(extension::equalsIgnoreCase)) {
            return Result.err(new MailError.AttachmentRejected(
                    "附件扩展名 \"" + extension + "\" 命中配置黑名单（toolbox.mail.attachment.blocked-extensions），拒绝发送"));
        }

        if (config.verifyMime()) {
            return verifyMime(attachment, filename);
        }
        return Result.ok();
    }

    private Result<Void, MailError> verifyMime(Attachment attachment, String filename) {
        if (!isTikaPresent()) {
            return Result.err(new MailError.AttachmentRejected(
                    "toolbox.mail.attachment.verify-mime=true 但运行时 classpath 缺少 org.apache.tika:tika-core，"
                            + "无法执行 MIME 魔数嗅探；请引入该 optional 依赖，或将 verify-mime 设为 false"));
        }
        return sniffAndCompare(attachment, filename);
    }

    /**
     * 真正调用 {@link MimeTyping}（进而触碰 Tika 类型）的逻辑拆到独立方法：只有
     * {@link #isTikaPresent()} 探测通过后才会执行到这里，保证探测失败分支永不触发
     * Tika 类的解析/初始化（同 storage 守卫）。
     */
    private Result<Void, MailError> sniffAndCompare(Attachment attachment, String filename) {
        String sniffed = MimeTyping.detect(attachment.bytes());
        String declared = attachment.contentType();
        if (declared != null && !declared.isBlank() && !declared.equalsIgnoreCase(sniffed)) {
            return Result.err(new MailError.AttachmentRejected(
                    "附件 \"" + filename + "\" MIME 校验失败：声明 Content-Type \"" + declared
                            + "\"，实际魔数嗅探为 \"" + sniffed + "\""));
        }
        return Result.ok();
    }

    /**
     * 惰性、缓存的 Tika 可用性探测（{@code initialize=false} 不触发类初始化，仅验证类可解析）。
     */
    private boolean isTikaPresent() {
        Boolean cached = tikaPresentCache;
        if (cached != null) {
            return cached;
        }
        boolean present;
        try {
            Class.forName(TIKA_PROBE_CLASS, false, probeClassLoader);
            present = true;
        } catch (ClassNotFoundException e) {
            present = false;
        }
        tikaPresentCache = present;
        return present;
    }
}
