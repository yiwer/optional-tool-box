package cn.code91.toolbox.storage.guard;

import cn.code91.facility.mime.MimeTyping;
import cn.code91.facility.path.Filenames;
import cn.code91.facility.result.Result;
import cn.code91.toolbox.storage.core.StorageError;
import cn.code91.toolbox.storage.core.ValidationError;

import java.io.IOException;
import java.io.InputStream;

/**
 * {@link StorageGuard} 默认实现。检查顺序（05 §4.3，固定不可调整，前一项失败即短路）：
 * <ol>
 *   <li>facility {@link Filenames} 清洗与危险扩展名</li>
 *   <li>max-size</li>
 *   <li>配置叠加的 blocked-extensions（与 facility 内置危险扩展名并集，而非替代）</li>
 *   <li>{@code verify-mime=true} 时 facility {@link MimeTyping} 魔数嗅探与声明 Content-Type 比对</li>
 * </ol>
 *
 * <p><b>MimeTyping/Tika 降级披露</b>：{@link MimeTyping} 的静态字段在类加载期直接
 * {@code new Tika()}，若消费方未引入 optional 的 {@code tika-core}，类加载会抛
 * {@code NoClassDefFoundError}（{@code Error} 而非 {@code Exception}，且 facility 侧
 * 无任何降级探测——不同于同仓库 {@code ExcelUtil} 的双类探测范式）。本类因此在真正调用
 * {@code MimeTyping} 之前先做一次惰性、缓存的<b>实例级</b> classpath 探测（P2 retrofit 为 mail
 * {@code DefaultAttachmentGuard} 的构造注入 probeClassLoader 范式），
 * {@code verify-mime=true} 但 Tika 不可用时，选择<b>拒绝该次上传</b>（{@code Err(ValidationError)}，
 * 消息提示补齐 {@code tika-core} 依赖）而非静默跳过嗅探——静默放行等于把用户显式打开的安全开关
 * 悄悄关掉，违背"不静默降级"（00-overview.md §2 原则 7）。</p>
 *
 * <p><b>流消费契约</b>：{@code verify-mime=false} 时本守卫完全不读取 {@code peekable} 流；
 * {@code verify-mime=true} 时要求流支持 {@code mark/reset}——嗅探前 {@code mark}、读取头部
 * 字节后 {@code reset} 复位，保证 05 §8"同一个流先 check 再 put"的示范用法成立（调用方
 * check 通过后仍可完整读到全部内容）；不支持 {@code mark/reset} 的流会被拒绝并返回带引导的
 * {@code ValidationError}（如何包装、或如何关闭开关），而非破坏性消费后放行——后者会让守卫
 * 本身成为上传内容被截断的原因。</p>
 */
public final class DefaultStorageGuard implements StorageGuard {

    private static final String TIKA_PROBE_CLASS = "org.apache.tika.Tika";

    /**
     * MIME 魔数嗅探读取的头部字节上限；Tika 魔数识别所需远小于此值，取 8KB 以覆盖
     * 个别头部偏移较深的容器格式。
     */
    private static final int SNIFF_HEAD_LIMIT = 8192;

    private final GuardConfig config;
    private final ClassLoader probeClassLoader;

    /**
     * 惰性单次探测结果缓存；<b>实例级</b>（P2 retrofit，同 mail {@code DefaultAttachmentGuard}
     * 范式）+ {@code volatile} 保证跨线程可见，无需额外同步（竞态下重复探测同一个只读判定，
     * 代价可忽略，同 ADR-0021 范式）。实例级使测试可用 {@code FilteredClassLoader} 真实演练
     * 缺 tika 分支，无需静态 override 钩子与复位纪律。
     */
    private volatile Boolean tikaPresentCache;

    /** 便捷构造：探测 ClassLoader 回退本类加载器（直接 new 使用的场景）。 */
    public DefaultStorageGuard(GuardConfig config) {
        this(config, DefaultStorageGuard.class.getClassLoader());
    }

    /**
     * @param probeClassLoader Tika classpath 探测所用 ClassLoader；装配层传容器侧
     *                         ClassLoader（{@code ResourceLoader#getClassLoader()}），使
     *                         {@code FilteredClassLoader} 条件测试对"缺 tika-core"分支
     *                         真实生效（同 mail 裁定 E 范式）。
     */
    public DefaultStorageGuard(GuardConfig config, ClassLoader probeClassLoader) {
        this.config = config;
        this.probeClassLoader = probeClassLoader;
    }

    @Override
    public Result<Void, StorageError> check(UploadCandidate candidate) {
        Result<String, StorageError> sanitized = sanitizeFilename(candidate.filename());
        if (sanitized.isErr()) {
            return Result.err(sanitized.getErr());
        }
        String filename = sanitized.get();

        if (Filenames.isDangerousExtension(filename)) {
            return Result.err(ValidationError.of(
                    "文件名 \"" + filename + "\" 使用危险扩展名（内置黑名单），拒绝上传"));
        }

        if (config.maxSize() > 0 && candidate.size() > config.maxSize()) {
            return Result.err(ValidationError.of(
                    "文件大小 " + candidate.size() + " 字节超过上限 " + config.maxSize() + " 字节"));
        }

        String extension = Filenames.extension(filename);
        if (extension != null && config.blockedExtensions().stream().anyMatch(extension::equalsIgnoreCase)) {
            return Result.err(ValidationError.of(
                    "文件扩展名 \"" + extension + "\" 命中配置黑名单（toolbox.storage.guard.blocked-extensions），拒绝上传"));
        }

        if (config.verifyMime()) {
            return verifyMime(candidate);
        }
        return Result.ok();
    }

    private Result<String, StorageError> sanitizeFilename(String filename) {
        Result<String, cn.code91.facility.error.WrappedError> sanitized = Filenames.sanitize(filename);
        if (sanitized.isErr()) {
            return Result.err(ValidationError.of(
                    "文件名非法：" + sanitized.getErr().getFormattedMessage() + "（原始文件名：\"" + filename + "\"）"));
        }
        return Result.ok(sanitized.get());
    }

    private Result<Void, StorageError> verifyMime(UploadCandidate candidate) {
        if (!isTikaPresent()) {
            return Result.err(ValidationError.of(
                    "toolbox.storage.guard.verify-mime=true 但运行时 classpath 缺少 org.apache.tika:tika-core，"
                            + "无法执行 MIME 魔数嗅探；请引入该 optional 依赖，或将 verify-mime 设为 false"));
        }
        return sniffAndCompare(candidate);
    }

    /**
     * 真正调用 {@link MimeTyping}（进而触碰 Tika 类型）的逻辑拆到独立方法：
     * 只有 {@link #isTikaPresent()} 探测通过后才会执行到这里，保证探测失败分支
     * 永不触发 Tika 类的解析/初始化。
     *
     * <p>嗅探经 {@code mark/reset} 保护（{@code mark} 的 readlimit 取嗅探上限 +1，
     * 确保恰好读满上限字节时 mark 仍有效）：读头部字节后立即 {@code reset} 复位，
     * 调用方之后从同一个流仍能完整读到全部内容——绝不破坏性消费（缺陷回归见
     * {@code DefaultStorageGuardTest#verifyMimeTruePreservesStreamForSubsequentFullRead}）。</p>
     */
    private Result<Void, StorageError> sniffAndCompare(UploadCandidate candidate) {
        InputStream stream = candidate.peekable();
        if (!stream.markSupported()) {
            return Result.err(ValidationError.of(
                    "toolbox.storage.guard.verify-mime=true 要求上传流支持 mark/reset（守卫需在嗅探后复位流，"
                            + "避免破坏性消费导致后续上传内容缺失）：请用 BufferedInputStream 包装上传流，"
                            + "或将 toolbox.storage.guard.verify-mime 设为 false"));
        }
        byte[] head;
        try {
            stream.mark(SNIFF_HEAD_LIMIT + 1);
            head = stream.readNBytes(SNIFF_HEAD_LIMIT);
            stream.reset();
        } catch (IOException e) {
            return Result.err(ValidationError.of("读取上传内容用于 MIME 嗅探时发生 IO 异常：" + e.getMessage()));
        }

        String sniffed = MimeTyping.detect(head);
        String declared = candidate.declaredContentType();
        if (declared != null && !declared.isBlank() && !declared.equalsIgnoreCase(sniffed)) {
            return Result.err(ValidationError.of(
                    "MIME 校验失败：声明 Content-Type \"" + declared + "\"，实际魔数嗅探为 \"" + sniffed + "\""));
        }
        return Result.ok();
    }

    /**
     * 惰性、缓存的 Tika 可用性探测（{@code initialize=false} 不触发类初始化，
     * 仅验证类可解析），经构造注入的 {@link #probeClassLoader} 探测（P2 retrofit，
     * 同 mail {@code DefaultAttachmentGuard#isTikaPresent} 范式）。
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
