package cn.code91.toolbox.storage.guard;

import cn.code91.facility.mime.MimeTyping;
import cn.code91.facility.path.Filenames;
import cn.code91.facility.result.Result;
import cn.code91.toolbox.storage.core.StorageError;
import cn.code91.toolbox.storage.core.ValidationError;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.SequenceInputStream;

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
 * {@code MimeTyping} 之前先做一次惰性、缓存的 classpath 探测（同一范式），
 * {@code verify-mime=true} 但 Tika 不可用时，选择<b>拒绝该次上传</b>（{@code Err(ValidationError)}，
 * 消息提示补齐 {@code tika-core} 依赖）而非静默跳过嗅探——静默放行等于把用户显式打开的安全开关
 * 悄悄关掉，违背"不静默降级"（00-overview.md §2 原则 7）。</p>
 */
public final class DefaultStorageGuard implements StorageGuard {

    private static final String TIKA_PROBE_CLASS = "org.apache.tika.Tika";

    /**
     * 惰性单次探测结果缓存；{@code volatile} 保证跨线程可见，无需额外同步
     * （竞态下重复探测同一个只读判定，代价可忽略，同 ADR-0021 范式）。
     */
    private static volatile Boolean tikaPresentCache;

    private final GuardConfig config;

    public DefaultStorageGuard(GuardConfig config) {
        this.config = config;
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
     */
    private Result<Void, StorageError> sniffAndCompare(UploadCandidate candidate) {
        byte[] head;
        InputStream restored;
        try {
            head = readHead(candidate.peekable());
            restored = new SequenceInputStream(new ByteArrayInputStream(head), candidate.peekable());
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
     * 读取用于嗅探的前置字节；不假设 {@code peekable} 支持 {@code mark/reset}，
     * 读取的字节通过 {@link SequenceInputStream} 拼回原流前部，保证调用方后续仍可
     * 完整读到全部内容（{@link UploadCandidate} 的 Javadoc 契约）。
     */
    private static byte[] readHead(InputStream stream) throws IOException {
        return stream.readNBytes(8192);
    }

    /**
     * 惰性、缓存的 Tika 可用性探测（{@code initialize=false} 不触发类初始化，
     * 仅验证类可解析），同 {@code ExcelUtil}/ADR-0021 范式。
     */
    private static boolean isTikaPresent() {
        Boolean cached = tikaPresentCache;
        if (cached != null) {
            return cached;
        }
        boolean present;
        try {
            Class.forName(TIKA_PROBE_CLASS, false, DefaultStorageGuard.class.getClassLoader());
            present = true;
        } catch (ClassNotFoundException e) {
            present = false;
        }
        tikaPresentCache = present;
        return present;
    }

    /**
     * 测试专用：强制探测缓存结果，模拟"classpath 缺 tika-core"分支而无需真的从测试
     * classpath 移除该依赖（同 {@code ExcelUtil} 的 {@code overridePoiPresent} 范式，
     * 见 server-facility ADR-0021）。测试须在 {@code @AfterEach} 中调用
     * {@link #resetTikaPresentCache()} 复原，避免污染后续测试。
     */
    static void overrideTikaPresent(boolean present) {
        tikaPresentCache = present;
    }

    /**
     * 复原探测缓存至"未探测"状态，令下一次调用重新走真实探测。
     */
    static void resetTikaPresentCache() {
        tikaPresentCache = null;
    }
}
