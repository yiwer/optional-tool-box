package cn.code91.toolbox.storage.guard;

import cn.code91.facility.result.Result;
import cn.code91.toolbox.storage.core.StorageError;
import cn.code91.toolbox.storage.core.ValidationError;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;
import java.io.ByteArrayOutputStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 守卫矩阵（05 §9）：路径穿越文件名、危险扩展名、超 max-size、配置黑名单扩展、
 * MIME 伪装（声明 image/jpeg 实为 zip 魔数）、verify-mime=false 放行。
 */
class DefaultStorageGuardTest {

    private static final GuardConfig DEFAULT_CONFIG = new GuardConfig(1024 * 1024, Set.of(), false);

    @AfterEach
    void resetTikaProbeCache() {
        // 毒化纪律：任何测试改动过探测缓存后必须复原，避免影响其余测试的真实探测路径。
        DefaultStorageGuard.resetTikaPresentCache();
    }

    @Test
    void rejectsPathTraversalFilename() {
        DefaultStorageGuard guard = new DefaultStorageGuard(DEFAULT_CONFIG);
        UploadCandidate candidate = candidateWith("../../etc/passwd", "text/plain", 10);

        Result<Void, StorageError> result = guard.check(candidate);

        assertThat(result.isErr()).isTrue();
        assertThat(result.getErr()).isInstanceOf(ValidationError.class);
        assertThat(result.getErr().message()).contains("文件名");
    }

    @Test
    void rejectsBlankFilename() {
        DefaultStorageGuard guard = new DefaultStorageGuard(DEFAULT_CONFIG);
        UploadCandidate candidate = candidateWith("   ", "text/plain", 10);

        Result<Void, StorageError> result = guard.check(candidate);

        assertThat(result.isErr()).isTrue();
        assertThat(result.getErr()).isInstanceOf(ValidationError.class);
    }

    @Test
    void rejectsFacilityDangerousExtension() {
        DefaultStorageGuard guard = new DefaultStorageGuard(DEFAULT_CONFIG);
        UploadCandidate candidate = candidateWith("virus.exe", "application/octet-stream", 10);

        Result<Void, StorageError> result = guard.check(candidate);

        assertThat(result.isErr()).isTrue();
        assertThat(result.getErr()).isInstanceOf(ValidationError.class);
        assertThat(result.getErr().message()).contains("exe");
    }

    @Test
    void rejectsOversizedUpload() {
        GuardConfig config = new GuardConfig(100, Set.of(), false);
        DefaultStorageGuard guard = new DefaultStorageGuard(config);
        UploadCandidate candidate = candidateWith("photo.jpg", "image/jpeg", 200);

        Result<Void, StorageError> result = guard.check(candidate);

        assertThat(result.isErr()).isTrue();
        assertThat(result.getErr()).isInstanceOf(ValidationError.class);
        assertThat(result.getErr().message()).contains("大小");
    }

    @Test
    void allowsUploadAtExactMaxSize() {
        GuardConfig config = new GuardConfig(100, Set.of(), false);
        DefaultStorageGuard guard = new DefaultStorageGuard(config);
        UploadCandidate candidate = candidateWith("photo.jpg", "image/jpeg", 100);

        Result<Void, StorageError> result = guard.check(candidate);

        assertThat(result.isOk()).isTrue();
    }

    @Test
    void rejectsConfiguredBlockedExtensionNotInFacilityDangerousSet() {
        // "zip" 不在 facility 内置危险扩展名集合中，验证"配置叠加"确实生效（并非只依赖内置集合）。
        GuardConfig config = new GuardConfig(1024, Set.of("zip"), false);
        DefaultStorageGuard guard = new DefaultStorageGuard(config);
        UploadCandidate candidate = candidateWith("archive.zip", "application/zip", 10);

        Result<Void, StorageError> result = guard.check(candidate);

        assertThat(result.isErr()).isTrue();
        assertThat(result.getErr()).isInstanceOf(ValidationError.class);
        assertThat(result.getErr().message()).contains("zip");
    }

    @Test
    void configuredBlockedExtensionIsCaseInsensitive() {
        GuardConfig config = new GuardConfig(1024, Set.of("zip"), false);
        DefaultStorageGuard guard = new DefaultStorageGuard(config);
        UploadCandidate candidate = candidateWith("archive.ZIP", "application/zip", 10);

        Result<Void, StorageError> result = guard.check(candidate);

        assertThat(result.isErr()).isTrue();
    }

    @Test
    void verifyMimeFalseAllowsDeclaredContentTypeMismatchWithActualBytes() throws Exception {
        // verify-mime=false：即便声明的 Content-Type 与实际魔数不符也放行（不做嗅探）。
        GuardConfig config = new GuardConfig(1024 * 1024, Set.of(), false);
        DefaultStorageGuard guard = new DefaultStorageGuard(config);
        byte[] zipBytes = zipMagicBytes();
        UploadCandidate candidate = new UploadCandidate("photo.jpg", "image/jpeg", zipBytes.length,
                new ByteArrayInputStream(zipBytes));

        Result<Void, StorageError> result = guard.check(candidate);

        assertThat(result.isOk()).isTrue();
    }

    @Test
    void verifyMimeTrueRejectsDisguisedContentType() throws Exception {
        // MIME 伪装：声明 image/jpeg，实际字节是 zip 魔数（PK\x03\x04）。
        GuardConfig config = new GuardConfig(1024 * 1024, Set.of(), true);
        DefaultStorageGuard guard = new DefaultStorageGuard(config);
        byte[] zipBytes = zipMagicBytes();
        UploadCandidate candidate = new UploadCandidate("photo.jpg", "image/jpeg", zipBytes.length,
                new ByteArrayInputStream(zipBytes));

        Result<Void, StorageError> result = guard.check(candidate);

        assertThat(result.isErr()).isTrue();
        assertThat(result.getErr()).isInstanceOf(ValidationError.class);
        assertThat(result.getErr().message()).contains("MIME");
    }

    @Test
    void verifyMimeTrueAllowsMatchingContentType() {
        GuardConfig config = new GuardConfig(1024 * 1024, Set.of(), true);
        DefaultStorageGuard guard = new DefaultStorageGuard(config);
        byte[] plainTextBytes = "hello world, this is plain text content for sniffing"
                .repeat(5).getBytes(StandardCharsets.UTF_8);
        UploadCandidate candidate = new UploadCandidate("notes.txt", "text/plain", plainTextBytes.length,
                new ByteArrayInputStream(plainTextBytes));

        Result<Void, StorageError> result = guard.check(candidate);

        assertThat(result.isOk()).isTrue();
    }

    @Test
    void verifyMimeTruePreservesStreamForSubsequentFullRead() throws IOException {
        // 缺陷回归（Important）：守卫嗅探不得破坏性消费调用方的流——05 §8 示范用法是同一个流
        // 先 check 再 put，check 通过后调用方必须仍能完整读到全部内容。修复前实现对流
        // readNBytes(8192) 后拼出的 SequenceInputStream 被当作局部变量丢弃，≤8KB 的上传
        // 经守卫后变成空流（本测试在修复前即因此 RED）。
        GuardConfig config = new GuardConfig(1024 * 1024, Set.of(), true);
        DefaultStorageGuard guard = new DefaultStorageGuard(config);
        byte[] content = "hello guard, please do not eat my upload bytes. "
                .repeat(10).getBytes(StandardCharsets.UTF_8);
        InputStream stream = new BufferedInputStream(new ByteArrayInputStream(content));
        UploadCandidate candidate = new UploadCandidate("notes.txt", "text/plain", content.length, stream);

        Result<Void, StorageError> result = guard.check(candidate);

        assertThat(result.isOk()).isTrue();
        assertThat(stream.readAllBytes())
                .as("check 通过后同一个流必须仍可完整读到原始内容（守卫不得破坏性消费）")
                .isEqualTo(content);
    }

    @Test
    void verifyMimeTrueRejectsStreamWithoutMarkResetSupport() {
        // 控制器裁定：verify-mime=true 且流不支持 mark/reset 时显式拒绝并给出引导，
        // 而非破坏性消费后放行（那会让守卫本身成为上传内容被截断的原因）。
        GuardConfig config = new GuardConfig(1024 * 1024, Set.of(), true);
        DefaultStorageGuard guard = new DefaultStorageGuard(config);
        InputStream nonMarkable = new FilterInputStream(
                new ByteArrayInputStream("payload".getBytes(StandardCharsets.UTF_8))) {
            @Override
            public boolean markSupported() {
                return false;
            }
        };
        UploadCandidate candidate = new UploadCandidate("notes.txt", "text/plain", 7, nonMarkable);

        Result<Void, StorageError> result = guard.check(candidate);

        assertThat(result.isErr()).isTrue();
        assertThat(result.getErr()).isInstanceOf(ValidationError.class);
        assertThat(result.getErr().message())
                .contains("mark/reset")
                .contains("BufferedInputStream")
                .contains("verify-mime");
    }

    @Test
    void verifyMimeFalseNeverTouchesStream() {
        // 钉住既有行为：verify-mime=false 时守卫不得读取流（哪怕流不支持 mark/reset 也应通过）。
        GuardConfig config = new GuardConfig(1024 * 1024, Set.of(), false);
        DefaultStorageGuard guard = new DefaultStorageGuard(config);
        AtomicInteger readCalls = new AtomicInteger();
        InputStream tracking = new FilterInputStream(
                new ByteArrayInputStream("payload".getBytes(StandardCharsets.UTF_8))) {
            @Override
            public boolean markSupported() {
                return false;
            }

            @Override
            public int read() throws IOException {
                readCalls.incrementAndGet();
                return super.read();
            }

            @Override
            public int read(byte[] b, int off, int len) throws IOException {
                readCalls.incrementAndGet();
                return super.read(b, off, len);
            }
        };
        UploadCandidate candidate = new UploadCandidate("notes.txt", "text/plain", 7, tracking);

        Result<Void, StorageError> result = guard.check(candidate);

        assertThat(result.isOk()).isTrue();
        assertThat(readCalls.get()).as("verify-mime=false 时流不得被守卫读取").isZero();
    }

    @Test
    void verifyMimeTrueRejectsWithGuidanceWhenTikaAbsentFromClasspath() {
        // 模拟消费方未引入 optional 的 tika-core：DefaultStorageGuard 必须显式拒绝该次上传
        // 并在消息中给出依赖引导，而不是静默跳过嗅探放行（00-overview.md §2 原则 7：不静默降级）。
        DefaultStorageGuard.overrideTikaPresent(false);
        GuardConfig config = new GuardConfig(1024 * 1024, Set.of(), true);
        DefaultStorageGuard guard = new DefaultStorageGuard(config);
        UploadCandidate candidate = candidateWith("photo.jpg", "image/jpeg", 10);

        Result<Void, StorageError> result = guard.check(candidate);

        assertThat(result.isErr()).isTrue();
        assertThat(result.getErr()).isInstanceOf(ValidationError.class);
        assertThat(result.getErr().message()).contains("tika-core");
    }

    private static UploadCandidate candidateWith(String filename, String contentType, long size) {
        InputStream stream = new ByteArrayInputStream("stub-content".getBytes(StandardCharsets.UTF_8));
        return new UploadCandidate(filename, contentType, size, stream);
    }

    /**
     * ZIP 魔数字节（PK\3\4）之后接一段可被 Deflate 压缩的内容，构造一个 Tika 能够真实
     * 识别为 application/zip 的最小可用负载（不追求合法 ZIP 结构，只需魔数可被嗅探）。
     */
    private static byte[] zipMagicBytes() throws Exception {
        ByteArrayOutputStream raw = new ByteArrayOutputStream();
        raw.write(new byte[]{0x50, 0x4B, 0x03, 0x04});
        ByteArrayOutputStream deflated = new ByteArrayOutputStream();
        DeflaterOutputStream dos = new DeflaterOutputStream(deflated, new Deflater(Deflater.BEST_COMPRESSION));
        dos.write("payload-content-for-zip-magic-sniffing-test".getBytes(StandardCharsets.UTF_8));
        dos.close();
        raw.write(deflated.toByteArray());
        return raw.toByteArray();
    }
}
