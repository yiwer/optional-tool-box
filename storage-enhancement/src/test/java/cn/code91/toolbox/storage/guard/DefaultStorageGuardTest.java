package cn.code91.toolbox.storage.guard;

import cn.code91.facility.result.Result;
import cn.code91.toolbox.storage.core.StorageError;
import cn.code91.toolbox.storage.core.ValidationError;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Set;
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
