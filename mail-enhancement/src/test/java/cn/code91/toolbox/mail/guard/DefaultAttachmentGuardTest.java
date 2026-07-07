package cn.code91.toolbox.mail.guard;

import cn.code91.facility.result.Result;
import cn.code91.toolbox.mail.core.Attachment;
import cn.code91.toolbox.mail.core.MailError;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.FilteredClassLoader;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 附件守卫矩阵（brief）：危险扩展名 / 超大小 / 配置黑名单 / verify-mime 开且 tika 缺失
 * fail-closed（FilteredClassLoader）/ verify-mime=false 放行伪装 MIME；另加文件名清洗与
 * verify-mime=true 的真实嗅探两态。
 */
class DefaultAttachmentGuardTest {

    private static final AttachmentGuardConfig PERMISSIVE = new AttachmentGuardConfig(0, Set.of(), false);

    @Test
    void rejectsPathTraversalFilename() {
        DefaultAttachmentGuard guard = new DefaultAttachmentGuard(PERMISSIVE);

        Result<Void, MailError> result = guard.check(Attachment.of("../../etc/passwd", bytes("x")));

        assertThat(result.isErr()).isTrue();
        assertThat(result.getErr()).isInstanceOf(MailError.AttachmentRejected.class);
        assertThat(result.getErr().message()).contains("文件名");
    }

    @Test
    void rejectsBlankFilename() {
        DefaultAttachmentGuard guard = new DefaultAttachmentGuard(PERMISSIVE);

        Result<Void, MailError> result = guard.check(Attachment.of("   ", bytes("x")));

        assertThat(result.isErr()).isTrue();
        assertThat(result.getErr()).isInstanceOf(MailError.AttachmentRejected.class);
    }

    @Test
    void rejectsFacilityDangerousExtension() {
        DefaultAttachmentGuard guard = new DefaultAttachmentGuard(PERMISSIVE);

        Result<Void, MailError> result = guard.check(Attachment.of("virus.exe", bytes("MZ...")));

        assertThat(result.isErr()).isTrue();
        assertThat(result.getErr()).isInstanceOf(MailError.AttachmentRejected.class);
        assertThat(result.getErr().message()).contains("exe");
    }

    @Test
    void rejectsOversizedAttachment() {
        AttachmentGuardConfig config = new AttachmentGuardConfig(10, Set.of(), false);
        DefaultAttachmentGuard guard = new DefaultAttachmentGuard(config);

        Result<Void, MailError> result = guard.check(Attachment.of("report.txt", bytes("0123456789A")));

        assertThat(result.isErr()).isTrue();
        assertThat(result.getErr()).isInstanceOf(MailError.AttachmentRejected.class);
        assertThat(result.getErr().message()).contains("大小");
    }

    @Test
    void allowsAttachmentAtExactMaxSize() {
        AttachmentGuardConfig config = new AttachmentGuardConfig(10, Set.of(), false);
        DefaultAttachmentGuard guard = new DefaultAttachmentGuard(config);

        Result<Void, MailError> result = guard.check(Attachment.of("report.txt", bytes("0123456789")));

        assertThat(result.isOk()).isTrue();
    }

    @Test
    void rejectsConfiguredBlockedExtensionNotInFacilityDangerousSet() {
        // "zip" 不在 facility 内置危险扩展名集合中，验证"配置叠加"确实生效。
        AttachmentGuardConfig config = new AttachmentGuardConfig(0, Set.of("zip"), false);
        DefaultAttachmentGuard guard = new DefaultAttachmentGuard(config);

        Result<Void, MailError> result = guard.check(Attachment.of("archive.zip", bytes("PK")));

        assertThat(result.isErr()).isTrue();
        assertThat(result.getErr().message()).contains("zip");
    }

    @Test
    void configuredBlockedExtensionIsCaseInsensitive() {
        AttachmentGuardConfig config = new AttachmentGuardConfig(0, Set.of("zip"), false);
        DefaultAttachmentGuard guard = new DefaultAttachmentGuard(config);

        Result<Void, MailError> result = guard.check(Attachment.of("archive.ZIP", bytes("PK")));

        assertThat(result.isErr()).isTrue();
    }

    @Test
    void verifyMimeFalseAllowsDisguisedContentType() throws Exception {
        // brief 矩阵行：verify-mime=false 放行伪装 MIME（声明 image/jpeg 实为 zip 魔数，不做嗅探）。
        DefaultAttachmentGuard guard = new DefaultAttachmentGuard(PERMISSIVE);

        Result<Void, MailError> result =
                guard.check(Attachment.of("photo.jpg", "image/jpeg", zipMagicBytes()));

        assertThat(result.isOk()).isTrue();
    }

    @Test
    void verifyMimeTrueRejectsDisguisedContentType() throws Exception {
        AttachmentGuardConfig config = new AttachmentGuardConfig(0, Set.of(), true);
        DefaultAttachmentGuard guard = new DefaultAttachmentGuard(config);

        Result<Void, MailError> result =
                guard.check(Attachment.of("photo.jpg", "image/jpeg", zipMagicBytes()));

        assertThat(result.isErr()).isTrue();
        assertThat(result.getErr()).isInstanceOf(MailError.AttachmentRejected.class);
        assertThat(result.getErr().message()).contains("MIME");
    }

    @Test
    void verifyMimeTrueAllowsMatchingContentType() {
        AttachmentGuardConfig config = new AttachmentGuardConfig(0, Set.of(), true);
        DefaultAttachmentGuard guard = new DefaultAttachmentGuard(config);
        byte[] plainText = "hello world, plain text content for sniffing".repeat(5)
                .getBytes(StandardCharsets.UTF_8);

        Result<Void, MailError> result = guard.check(Attachment.of("notes.txt", "text/plain", plainText));

        assertThat(result.isOk()).isTrue();
    }

    @Test
    void verifyMimeTrueWithoutDeclaredContentTypeSniffsAndPasses() {
        // 声明 Content-Type 缺失时无从比对：嗅探正常执行但不拦（与 storage 守卫同语义）。
        AttachmentGuardConfig config = new AttachmentGuardConfig(0, Set.of(), true);
        DefaultAttachmentGuard guard = new DefaultAttachmentGuard(config);

        Result<Void, MailError> result =
                guard.check(Attachment.of("notes.txt", bytes("plain text content")));

        assertThat(result.isOk()).isTrue();
    }

    @Test
    void verifyMimeTrueFailsClosedWithGuidanceWhenTikaAbsent() {
        // brief 矩阵行（裁定 E）：verify-mime 开且 tika 缺失 → fail-closed 带依赖引导。
        // FilteredClassLoader 屏蔽 org.apache.tika 包，探测走真实 Class.forName 失败分支。
        AttachmentGuardConfig config = new AttachmentGuardConfig(0, Set.of(), true);
        DefaultAttachmentGuard guard =
                new DefaultAttachmentGuard(config, new FilteredClassLoader("org.apache.tika"));

        Result<Void, MailError> result = guard.check(Attachment.of("photo.jpg", "image/jpeg", bytes("x")));

        assertThat(result.isErr()).isTrue();
        assertThat(result.getErr()).isInstanceOf(MailError.AttachmentRejected.class);
        assertThat(result.getErr().message()).contains("tika-core").contains("verify-mime");
    }

    @Test
    void verifyMimeFalseNeverProbesTikaEvenWhenAbsent() {
        // 钉住：verify-mime=false 时完全不触碰 Tika 探测（缺 tika 也不影响其余检查）。
        DefaultAttachmentGuard guard =
                new DefaultAttachmentGuard(PERMISSIVE, new FilteredClassLoader("org.apache.tika"));

        Result<Void, MailError> result = guard.check(Attachment.of("notes.txt", bytes("plain")));

        assertThat(result.isOk()).isTrue();
    }

    private static byte[] bytes(String content) {
        return content.getBytes(StandardCharsets.UTF_8);
    }

    /**
     * ZIP 魔数字节（PK\3\4）之后接一段 Deflate 压缩内容，构造 Tika 能真实识别为
     * application/zip 的最小负载（同 storage 守卫测试的构造方式）。
     */
    private static byte[] zipMagicBytes() throws Exception {
        ByteArrayOutputStream raw = new ByteArrayOutputStream();
        raw.write(new byte[]{0x50, 0x4B, 0x03, 0x04});
        ByteArrayOutputStream deflated = new ByteArrayOutputStream();
        try (DeflaterOutputStream dos = new DeflaterOutputStream(deflated, new Deflater(Deflater.BEST_COMPRESSION))) {
            dos.write("payload-content-for-zip-magic-sniffing-test".getBytes(StandardCharsets.UTF_8));
        }
        raw.write(deflated.toByteArray());
        return raw.toByteArray();
    }
}
