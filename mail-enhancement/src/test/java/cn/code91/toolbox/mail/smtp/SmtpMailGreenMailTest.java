package cn.code91.toolbox.mail.smtp;

import cn.code91.facility.result.Result;
import cn.code91.toolbox.mail.core.Attachment;
import cn.code91.toolbox.mail.core.MailError;
import cn.code91.toolbox.mail.core.MailMessage;
import cn.code91.toolbox.mail.core.MailReceipt;
import cn.code91.toolbox.mail.guard.AttachmentGuardConfig;
import cn.code91.toolbox.mail.guard.DefaultAttachmentGuard;
import cn.code91.toolbox.mail.sandbox.SandboxPolicy;
import cn.code91.toolbox.mail.spi.MailSendListener;
import cn.code91.toolbox.mail.template.SimpleTemplateRenderer;
import com.icegreen.greenmail.configuration.GreenMailConfiguration;
import com.icegreen.greenmail.junit5.GreenMailExtension;
import com.icegreen.greenmail.util.ServerSetupTest;
import jakarta.mail.BodyPart;
import jakarta.mail.Message;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.mail.javamail.JavaMailSenderImpl;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * GreenMail 全回环矩阵（brief）：单账号发文本/HTML/附件、多账号并存、认证失败→AuthError、
 * 连接拒绝→ConnectError 且重试次数可断言、模板渲染端到端、沙箱 redirect/白名单两态端到端。
 * GreenMail 为内嵌 SMTP，无需 Docker；扩展默认逐测试方法启停，互不串扰。
 */
class SmtpMailGreenMailTest {

    @RegisterExtension
    final GreenMailExtension greenMail = new GreenMailExtension(ServerSetupTest.SMTP)
            .withConfiguration(GreenMailConfiguration.aConfig()
                    .withUser("notice@code91.cn", "notice", "notice-pass")
                    .withUser("alert@code91.cn", "alert", "alert-pass"));

    @Test
    void plainTextMailRoundTrips() throws Exception {
        SmtpMailDispatcher dispatcher = noticeDispatcher();

        Result<MailReceipt, MailError> result = dispatcher.send(MailMessage.builder()
                .to("user@customer.com")
                .subject("对账通知")
                .text("今日对账差异 0 笔。")
                .build());

        assertThat(result.isOk()).isTrue();
        assertThat(result.get().account()).isEqualTo("notice");
        assertThat(result.get().messageId()).isNotBlank();
        assertThat(greenMail.waitForIncomingEmail(5000, 1)).isTrue();
        MimeMessage received = greenMail.getReceivedMessages()[0];
        assertThat(received.getSubject()).isEqualTo("对账通知");
        assertThat(((InternetAddress) received.getFrom()[0]).getAddress()).isEqualTo("notice@code91.cn");
        assertThat(received.getRecipients(Message.RecipientType.TO)[0].toString()).isEqualTo("user@customer.com");
        assertThat(String.valueOf(received.getContent())).contains("今日对账差异 0 笔。");
    }

    @Test
    void rawHtmlMailRoundTripsWithHtmlContentType() throws Exception {
        SmtpMailDispatcher dispatcher = noticeDispatcher();

        Result<MailReceipt, MailError> result = dispatcher.send(MailMessage.builder()
                .to("user@customer.com")
                .subject("HTML 通知")
                .rawHtml("<h1>告警</h1><p>阈值超限</p>")
                .build());

        assertThat(result.isOk()).isTrue();
        assertThat(greenMail.waitForIncomingEmail(5000, 1)).isTrue();
        MimeMessage received = greenMail.getReceivedMessages()[0];
        assertThat(received.getContentType()).contains("text/html");
        assertThat(String.valueOf(received.getContent())).contains("<h1>告警</h1>");
    }

    @Test
    void attachmentRoundTripsWithNameAndBytes() throws Exception {
        SmtpMailDispatcher dispatcher = noticeDispatcher();
        byte[] payload = "对账单内容-reconciliation-data".getBytes(StandardCharsets.UTF_8);

        Result<MailReceipt, MailError> result = dispatcher.send(MailMessage.builder()
                .to("user@customer.com")
                .subject("对账单")
                .text("见附件")
                .attach(Attachment.of("recon.csv", "text/csv", payload))
                .build());

        assertThat(result.isOk()).isTrue();
        assertThat(greenMail.waitForIncomingEmail(5000, 1)).isTrue();
        MimeMessage received = greenMail.getReceivedMessages()[0];
        BodyPart attachmentPart = findAttachmentPart((MimeMultipart) received.getContent(), "recon.csv");
        assertThat(attachmentPart).as("应收到文件名为 recon.csv 的附件").isNotNull();
        assertThat(attachmentPart.getContentType()).contains("text/csv");
        assertThat(readAll(attachmentPart.getInputStream())).isEqualTo(payload);
    }

    @Test
    void templateRendersEndToEnd() throws Exception {
        SmtpMailDispatcher dispatcher = noticeDispatcher();

        Result<MailReceipt, MailError> result = dispatcher.send(MailMessage.builder()
                .to("user@customer.com")
                .subject("欢迎")
                .html("welcome", Map.of("name", "张三", "code", 246810, "remark", "尽快使用"))
                .build());

        assertThat(result.isOk()).isTrue();
        assertThat(greenMail.waitForIncomingEmail(5000, 1)).isTrue();
        MimeMessage received = greenMail.getReceivedMessages()[0];
        String content = String.valueOf(received.getContent());
        assertThat(content).contains("你好，张三！");
        assertThat(content).contains("246810");
        assertThat(content).doesNotContain("${");
    }

    @Test
    void multipleAccountsSendIndependently() throws Exception {
        SmtpMailDispatcher notice = noticeDispatcher();
        SmtpMailDispatcher alert = dispatcher("alert", "alert", "alert-pass", "alert@code91.cn");

        assertThat(notice.send(MailMessage.builder()
                .to("a@customer.com").subject("notice-mail").text("n").build()).isOk()).isTrue();
        assertThat(alert.send(MailMessage.builder()
                .to("b@customer.com").subject("alert-mail").text("a").build()).isOk()).isTrue();

        assertThat(greenMail.waitForIncomingEmail(5000, 2)).isTrue();
        MimeMessage[] received = greenMail.getReceivedMessages();
        List<String> froms = new ArrayList<>();
        for (MimeMessage message : received) {
            froms.add(((InternetAddress) message.getFrom()[0]).getAddress());
        }
        assertThat(froms).containsExactlyInAnyOrder("notice@code91.cn", "alert@code91.cn");
    }

    @Test
    void wrongPasswordYieldsAuthErrorAndNothingDelivered() {
        SmtpMailDispatcher dispatcher = dispatcher("notice", "notice", "wrong-pass", "notice@code91.cn");

        Result<MailReceipt, MailError> result = dispatcher.send(MailMessage.builder()
                .to("user@customer.com").subject("s").text("t").build());

        assertThat(result.isErr()).isTrue();
        assertThat(result.getErr()).isInstanceOf(MailError.AuthError.class);
        assertThat(greenMail.getReceivedMessages()).isEmpty();
    }

    @Test
    void connectionRefusedYieldsConnectErrorAfterConfiguredRetries() throws Exception {
        int unusedPort = findFreePort();
        SmtpConnectionConfig connection = new SmtpConnectionConfig(
                "dead", "localhost", unusedPort, false, null, null, null, Duration.ofSeconds(2));
        JavaMailSenderImpl sender = JavaMailSenderFactory.create(connection);
        SmtpDispatchSettings settings = new SmtpDispatchSettings(
                "dead", "dead@code91.cn", null, 0, 3, Duration.ofMillis(50));
        List<Duration> sleeps = new ArrayList<>();
        SmtpMailDispatcher dispatcher = new SmtpMailDispatcher(
                sender, settings, SandboxPolicy.disabled(), new SimpleTemplateRenderer(),
                permissiveGuard(), List.of(), (account, perMinute) -> null, sleeps::add);

        Result<MailReceipt, MailError> result = dispatcher.send(MailMessage.builder()
                .to("user@customer.com").subject("s").text("t").build());

        assertThat(result.isErr()).isTrue();
        assertThat(result.getErr()).isInstanceOf(MailError.ConnectError.class);
        // 真实连接拒绝路径的重试次数断言：3 次尝试之间恰有 2 次退避（50ms→100ms 倍增）。
        assertThat(sleeps).containsExactly(Duration.ofMillis(50), Duration.ofMillis(100));
    }

    @Test
    void sandboxRedirectDeliversToRedirectTargetWithOriginalToHeader() throws Exception {
        // "生产误开沙箱但配了 redirect"路径：装配可用、发送正常，只是被改道（裁定 B）。
        SandboxPolicy redirect = new SandboxPolicy(true, "qa-inbox@code91.cn", null, null);
        SmtpMailDispatcher dispatcher = noticeDispatcher(redirect, List.of());

        Result<MailReceipt, MailError> result = dispatcher.send(MailMessage.builder()
                .to("real-user@customer.com")
                .subject("沙箱重定向")
                .text("t")
                .build());

        assertThat(result.isOk()).as("redirect 场景对调用方是正常回执，而非 Err").isTrue();
        assertThat(greenMail.waitForIncomingEmail(5000, 1)).isTrue();
        MimeMessage received = greenMail.getReceivedMessages()[0];
        assertThat(received.getRecipients(Message.RecipientType.TO)[0].toString())
                .isEqualTo("qa-inbox@code91.cn");
        assertThat(received.getHeader("X-Original-To", null)).isEqualTo("real-user@customer.com");
    }

    @Test
    void sandboxWhitelistMissBlocksAndNothingDelivered() {
        SandboxPolicy whitelist = new SandboxPolicy(true, null, List.of("code91.cn"), null);
        RecordingListener listener = new RecordingListener();
        SmtpMailDispatcher dispatcher = noticeDispatcher(whitelist, List.of(listener));

        Result<MailReceipt, MailError> result = dispatcher.send(MailMessage.builder()
                .to("real-user@customer.com").subject("s").text("t").build());

        assertThat(result.isErr()).isTrue();
        assertThat(result.getErr()).isInstanceOf(MailError.SandboxBlocked.class);
        assertThat(((MailError.SandboxBlocked) result.getErr()).originalRecipients())
                .containsExactly("real-user@customer.com");
        assertThat(greenMail.getReceivedMessages()).isEmpty();
        assertThat(listener.failures).hasSize(1);
    }

    @Test
    void sandboxWhitelistHitDeliversNormally() throws Exception {
        SandboxPolicy whitelist = new SandboxPolicy(true, null, List.of("code91.cn"), null);
        SmtpMailDispatcher dispatcher = noticeDispatcher(whitelist, List.of());

        Result<MailReceipt, MailError> result = dispatcher.send(MailMessage.builder()
                .to("dev@code91.cn").subject("s").text("t").build());

        assertThat(result.isOk()).isTrue();
        assertThat(greenMail.waitForIncomingEmail(5000, 1)).isTrue();
        assertThat(greenMail.getReceivedMessages()[0].getRecipients(Message.RecipientType.TO)[0].toString())
                .isEqualTo("dev@code91.cn");
    }

    private SmtpMailDispatcher noticeDispatcher() {
        return dispatcher("notice", "notice", "notice-pass", "notice@code91.cn");
    }

    private SmtpMailDispatcher noticeDispatcher(SandboxPolicy sandbox, List<MailSendListener> listeners) {
        JavaMailSenderImpl sender = JavaMailSenderFactory.create(connectionConfig("notice", "notice", "notice-pass"));
        SmtpDispatchSettings settings = new SmtpDispatchSettings(
                "notice", "notice@code91.cn", null, 0, 3, Duration.ofMillis(50));
        return new SmtpMailDispatcher(sender, settings, sandbox,
                new SimpleTemplateRenderer(), permissiveGuard(), listeners);
    }

    private SmtpMailDispatcher dispatcher(String account, String username, String password, String from) {
        JavaMailSenderImpl sender = JavaMailSenderFactory.create(connectionConfig(account, username, password));
        SmtpDispatchSettings settings = new SmtpDispatchSettings(account, from, null, 0, 3, Duration.ofMillis(50));
        return new SmtpMailDispatcher(sender, settings, SandboxPolicy.disabled(),
                new SimpleTemplateRenderer(), permissiveGuard(), List.of());
    }

    private SmtpConnectionConfig connectionConfig(String account, String username, String password) {
        return new SmtpConnectionConfig(account, "localhost", greenMail.getSmtp().getPort(),
                false, username, password, null, Duration.ofSeconds(5));
    }

    private static DefaultAttachmentGuard permissiveGuard() {
        return new DefaultAttachmentGuard(new AttachmentGuardConfig(0, Set.of(), false));
    }

    private static BodyPart findAttachmentPart(MimeMultipart multipart, String filename) throws Exception {
        for (int i = 0; i < multipart.getCount(); i++) {
            BodyPart part = multipart.getBodyPart(i);
            if (filename.equals(part.getFileName())) {
                return part;
            }
            if (part.getContent() instanceof MimeMultipart nested) {
                BodyPart found = findAttachmentPart(nested, filename);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private static byte[] readAll(InputStream stream) throws Exception {
        try (ByteArrayOutputStream buffer = new ByteArrayOutputStream(); stream) {
            stream.transferTo(buffer);
            return buffer.toByteArray();
        }
    }

    private static int findFreePort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    /**
     * 记录式监听器（GreenMail 侧复用）。
     */
    static final class RecordingListener implements MailSendListener {

        final List<MailReceipt> successes = new ArrayList<>();
        final List<MailError> failures = new ArrayList<>();

        @Override
        public void onSuccess(MailMessage message, MailReceipt receipt) {
            successes.add(receipt);
        }

        @Override
        public void onFailure(MailMessage message, MailError error) {
            failures.add(error);
        }
    }
}
