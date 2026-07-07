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
import cn.code91.toolbox.mail.testfixtures.CapturingMailSender;
import cn.code91.toolbox.mail.testfixtures.RecordingListener;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.Test;
import org.springframework.mail.MailAuthenticationException;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSender;

import java.net.ConnectException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link SmtpMailDispatcher} 编排单测（经内存捕获式 {@link JavaMailSender} 桩，不走网络）：
 * 限速短路 / 沙箱三态 / 发件人解析 / 模板与守卫错误穿透 / 重试语义（仅 ConnectError、
 * 指数倍增、次数上限、中断放弃）/ 回调全通知且异常吞掉 / sendAsync 语义一致。
 */
class SmtpMailDispatcherTest {

    private static final SmtpDispatchSettings SETTINGS = new SmtpDispatchSettings(
            "notice", "notice@code91.cn", "Notice Center", 0, 3, Duration.ofMillis(200));

    @Test
    void successfulSendCapturesEnvelopeAndReturnsReceipt() throws Exception {
        CapturingMailSender sender = new CapturingMailSender();
        SmtpMailDispatcher dispatcher = dispatcher(sender, SETTINGS, SandboxPolicy.disabled(), List.of());

        Result<MailReceipt, MailError> result = dispatcher.send(MailMessage.builder()
                .to("a@x.com").cc("c@x.com").bcc("b@x.com")
                .replyTo("reply@code91.cn")
                .subject("对账通知")
                .text("正文内容")
                .build());

        assertThat(result.isOk()).isTrue();
        assertThat(result.get().account()).isEqualTo("notice");
        assertThat(result.get().messageId()).isNotNull();
        assertThat(result.get().sentAt()).isNotNull();
        assertThat(sender.sent).hasSize(1);
        MimeMessage mime = sender.sent.get(0);
        InternetAddress from = (InternetAddress) mime.getFrom()[0];
        assertThat(from.getAddress()).isEqualTo("notice@code91.cn");
        assertThat(from.getPersonal()).isEqualTo("Notice Center");
        assertThat(mime.getRecipients(Message.RecipientType.TO)[0].toString()).isEqualTo("a@x.com");
        assertThat(mime.getRecipients(Message.RecipientType.CC)[0].toString()).isEqualTo("c@x.com");
        assertThat(mime.getRecipients(Message.RecipientType.BCC)[0].toString()).isEqualTo("b@x.com");
        assertThat(mime.getReplyTo()[0].toString()).isEqualTo("reply@code91.cn");
        assertThat(mime.getSubject()).isEqualTo("对账通知");
    }

    @Test
    void messageFromOverridesAccountFrom() throws Exception {
        CapturingMailSender sender = new CapturingMailSender();
        SmtpMailDispatcher dispatcher = dispatcher(sender, SETTINGS, SandboxPolicy.disabled(), List.of());

        Result<MailReceipt, MailError> result = dispatcher.send(MailMessage.builder()
                .from("explicit@code91.cn").to("a@x.com").text("t").build());

        assertThat(result.isOk()).isTrue();
        assertThat(((InternetAddress) sender.sent.get(0).getFrom()[0]).getAddress())
                .isEqualTo("explicit@code91.cn");
    }

    @Test
    void missingFromEverywhereReturnsConfigError() {
        CapturingMailSender sender = new CapturingMailSender();
        SmtpDispatchSettings noFrom = new SmtpDispatchSettings("spring", null, null, 0, 1, null);
        SmtpMailDispatcher dispatcher = dispatcher(sender, noFrom, SandboxPolicy.disabled(), List.of());

        Result<MailReceipt, MailError> result =
                dispatcher.send(MailMessage.builder().to("a@x.com").text("t").build());

        assertThat(result.isErr()).isTrue();
        assertThat(result.getErr()).isInstanceOf(MailError.ConfigError.class);
        assertThat(result.getErr().message()).contains("from");
        assertThat(sender.sent).isEmpty();
    }

    @Test
    void missingBodyReturnsConfigError() {
        CapturingMailSender sender = new CapturingMailSender();
        SmtpMailDispatcher dispatcher = dispatcher(sender, SETTINGS, SandboxPolicy.disabled(), List.of());

        Result<MailReceipt, MailError> result =
                dispatcher.send(MailMessage.builder().to("a@x.com").build());

        assertThat(result.isErr()).isTrue();
        assertThat(result.getErr()).isInstanceOf(MailError.ConfigError.class);
    }

    @Test
    void emptyRecipientsReturnsInvalidRecipient() {
        CapturingMailSender sender = new CapturingMailSender();
        SmtpMailDispatcher dispatcher = dispatcher(sender, SETTINGS, SandboxPolicy.disabled(), List.of());

        Result<MailReceipt, MailError> result =
                dispatcher.send(MailMessage.builder().subject("s").text("t").build());

        assertThat(result.isErr()).isTrue();
        assertThat(result.getErr()).isInstanceOf(MailError.InvalidRecipient.class);
    }

    @Test
    void rateLimitHitShortCircuitsWithSuggestedWaitAndNotifiesFailure() {
        CapturingMailSender sender = new CapturingMailSender();
        RecordingListener listener = new RecordingListener();
        SmtpDispatchSettings limited = new SmtpDispatchSettings(
                "alert", "alert@code91.cn", null, 20, 3, Duration.ofMillis(10));
        SmtpMailDispatcher dispatcher = new SmtpMailDispatcher(
                sender, limited, SandboxPolicy.disabled(), new SimpleTemplateRenderer(),
                permissiveGuard(), List.of(listener),
                (account, perMinute) -> Duration.ofSeconds(30), noSleep());

        Result<MailReceipt, MailError> result =
                dispatcher.send(MailMessage.builder().to("a@x.com").text("t").build());

        assertThat(result.isErr()).isTrue();
        assertThat(result.getErr()).isInstanceOf(MailError.RateLimited.class);
        assertThat(((MailError.RateLimited) result.getErr()).suggestedWait()).isEqualTo(Duration.ofSeconds(30));
        assertThat(sender.sent).isEmpty();
        assertThat(listener.failures).hasSize(1);
        assertThat(listener.successes).isEmpty();
    }

    @Test
    void zeroRateLimitNeverConsultsGate() {
        CapturingMailSender sender = new CapturingMailSender();
        SmtpMailDispatcher dispatcher = new SmtpMailDispatcher(
                sender, SETTINGS, SandboxPolicy.disabled(), new SimpleTemplateRenderer(),
                permissiveGuard(), List.of(),
                (account, perMinute) -> {
                    throw new AssertionError("rate-limit-per-minute=0 不得触碰限速门");
                }, noSleep());

        Result<MailReceipt, MailError> result =
                dispatcher.send(MailMessage.builder().to("a@x.com").text("t").build());

        assertThat(result.isOk()).isTrue();
    }

    @Test
    void sandboxBlockedReturnsErrWithOriginalRecipients() {
        CapturingMailSender sender = new CapturingMailSender();
        SandboxPolicy whitelist = new SandboxPolicy(true, null, List.of("code91.cn"), null);
        SmtpMailDispatcher dispatcher = dispatcher(sender, SETTINGS, whitelist, List.of());

        Result<MailReceipt, MailError> result = dispatcher.send(MailMessage.builder()
                .to("dev@code91.cn", "real-user@customer.com").text("t").build());

        assertThat(result.isErr()).isTrue();
        assertThat(result.getErr()).isInstanceOf(MailError.SandboxBlocked.class);
        MailError.SandboxBlocked blocked = (MailError.SandboxBlocked) result.getErr();
        assertThat(blocked.originalRecipients()).containsExactly("dev@code91.cn", "real-user@customer.com");
        assertThat(blocked.message()).contains("real-user@customer.com");
        assertThat(sender.sent).isEmpty();
    }

    @Test
    void sandboxRedirectRewritesEnvelopeAndKeepsOriginalsInHeader() throws Exception {
        CapturingMailSender sender = new CapturingMailSender();
        SandboxPolicy redirect = new SandboxPolicy(true, "qa-inbox@code91.cn", null, null);
        SmtpMailDispatcher dispatcher = dispatcher(sender, SETTINGS, redirect, List.of());

        Result<MailReceipt, MailError> result = dispatcher.send(MailMessage.builder()
                .to("real-user@customer.com").cc("boss@customer.com").text("t").build());

        assertThat(result.isOk()).isTrue();
        MimeMessage mime = sender.sent.get(0);
        assertThat(mime.getRecipients(Message.RecipientType.TO)).hasSize(1);
        assertThat(mime.getRecipients(Message.RecipientType.TO)[0].toString()).isEqualTo("qa-inbox@code91.cn");
        assertThat(mime.getRecipients(Message.RecipientType.CC)).isNull();
        assertThat(mime.getHeader("X-Original-To", null))
                .isEqualTo("real-user@customer.com, boss@customer.com");
    }

    @Test
    void templateErrorPassesThroughWithoutSending() {
        CapturingMailSender sender = new CapturingMailSender();
        SmtpMailDispatcher dispatcher = dispatcher(sender, SETTINGS, SandboxPolicy.disabled(), List.of());

        Result<MailReceipt, MailError> result = dispatcher.send(MailMessage.builder()
                .to("a@x.com").html("no-such-template", Map.of()).build());

        assertThat(result.isErr()).isTrue();
        assertThat(result.getErr()).isInstanceOf(MailError.TemplateError.class);
        assertThat(sender.sent).isEmpty();
    }

    @Test
    void rejectedAttachmentPassesThroughWithoutSending() {
        CapturingMailSender sender = new CapturingMailSender();
        SmtpMailDispatcher dispatcher = dispatcher(sender, SETTINGS, SandboxPolicy.disabled(), List.of());

        Result<MailReceipt, MailError> result = dispatcher.send(MailMessage.builder()
                .to("a@x.com").text("t")
                .attach(Attachment.of("virus.exe", new byte[]{1}))
                .build());

        assertThat(result.isErr()).isTrue();
        assertThat(result.getErr()).isInstanceOf(MailError.AttachmentRejected.class);
        assertThat(sender.sent).isEmpty();
    }

    @Test
    void connectErrorRetriesWithExponentialBackoffUntilMaxAttempts() {
        CapturingMailSender sender = new CapturingMailSender();
        sender.failWith = connectionRefused();
        List<Duration> sleeps = new ArrayList<>();
        SmtpMailDispatcher dispatcher = new SmtpMailDispatcher(
                sender, SETTINGS, SandboxPolicy.disabled(), new SimpleTemplateRenderer(),
                permissiveGuard(), List.of(), (a, p) -> null, sleeps::add);

        Result<MailReceipt, MailError> result =
                dispatcher.send(MailMessage.builder().to("a@x.com").text("t").build());

        assertThat(result.isErr()).isTrue();
        assertThat(result.getErr()).isInstanceOf(MailError.ConnectError.class);
        // 裁定 C：max-attempts=3 → 共尝试 3 次，间隔 200ms、400ms 指数倍增。
        assertThat(sender.sendAttempts.get()).isEqualTo(3);
        assertThat(sleeps).containsExactly(Duration.ofMillis(200), Duration.ofMillis(400));
    }

    @Test
    void authErrorIsNotRetried() {
        CapturingMailSender sender = new CapturingMailSender();
        sender.failWith = new MailAuthenticationException(
                new jakarta.mail.AuthenticationFailedException("535 rejected"));
        List<Duration> sleeps = new ArrayList<>();
        SmtpMailDispatcher dispatcher = new SmtpMailDispatcher(
                sender, SETTINGS, SandboxPolicy.disabled(), new SimpleTemplateRenderer(),
                permissiveGuard(), List.of(), (a, p) -> null, sleeps::add);

        Result<MailReceipt, MailError> result =
                dispatcher.send(MailMessage.builder().to("a@x.com").text("t").build());

        assertThat(result.isErr()).isTrue();
        assertThat(result.getErr()).isInstanceOf(MailError.AuthError.class);
        assertThat(sender.sendAttempts.get()).isEqualTo(1);
        assertThat(sleeps).isEmpty();
    }

    @Test
    void interruptedBackoffAbortsRemainingRetriesAndRestoresFlag() throws Exception {
        CapturingMailSender sender = new CapturingMailSender();
        sender.failWith = connectionRefused();
        SmtpMailDispatcher dispatcher = new SmtpMailDispatcher(
                sender, SETTINGS, SandboxPolicy.disabled(), new SimpleTemplateRenderer(),
                permissiveGuard(), List.of(), (a, p) -> null,
                duration -> {
                    throw new InterruptedException("interrupted");
                });

        // 在独立线程执行以免污染测试线程的中断位。
        final Result<?, ?>[] holder = new Result<?, ?>[1];
        final boolean[] interrupted = new boolean[1];
        Thread worker = new Thread(() -> {
            holder[0] = dispatcher.send(MailMessage.builder().to("a@x.com").text("t").build());
            interrupted[0] = Thread.currentThread().isInterrupted();
        });
        worker.start();
        worker.join(5000);

        assertThat(holder[0].isErr()).isTrue();
        assertThat(((Result<?, ?>) holder[0]).getErr()).isInstanceOf(MailError.ConnectError.class);
        assertThat(sender.sendAttempts.get()).isEqualTo(1);
        assertThat(interrupted[0]).as("中断标志位必须被恢复").isTrue();
    }

    @Test
    void allListenersNotifiedEvenWhenOneThrows() {
        CapturingMailSender sender = new CapturingMailSender();
        RecordingListener second = new RecordingListener();
        MailSendListener throwing = new MailSendListener() {
            @Override
            public void onSuccess(MailMessage message, MailReceipt receipt) {
                throw new IllegalStateException("listener boom");
            }
        };
        SmtpMailDispatcher dispatcher =
                dispatcher(sender, SETTINGS, SandboxPolicy.disabled(), List.of(throwing, second));

        Result<MailReceipt, MailError> result =
                dispatcher.send(MailMessage.builder().to("a@x.com").text("t").build());

        assertThat(result.isOk()).as("回调异常不得影响发送结果").isTrue();
        assertThat(second.successes).hasSize(1);
    }

    @Test
    void sendAsyncCompletesWithSameSemantics() {
        CapturingMailSender sender = new CapturingMailSender();
        SmtpMailDispatcher dispatcher = dispatcher(sender, SETTINGS, SandboxPolicy.disabled(), List.of());

        Result<MailReceipt, MailError> result = dispatcher
                .sendAsync(MailMessage.builder().to("a@x.com").text("t").build())
                .join();

        assertThat(result.isOk()).isTrue();
        assertThat(sender.sent).hasSize(1);
    }

    private static SmtpMailDispatcher dispatcher(JavaMailSender sender, SmtpDispatchSettings settings,
                                                 SandboxPolicy sandbox, List<MailSendListener> listeners) {
        return new SmtpMailDispatcher(sender, settings, sandbox, new SimpleTemplateRenderer(),
                permissiveGuard(), listeners, (account, perMinute) -> null, noSleep());
    }

    private static DefaultAttachmentGuard permissiveGuard() {
        return new DefaultAttachmentGuard(new AttachmentGuardConfig(0, Set.of(), false));
    }

    private static SmtpMailDispatcher.Sleeper noSleep() {
        return duration -> {
        };
    }

    private static MailSendException connectionRefused() {
        return new MailSendException("Mail server connection failed",
                new MessagingException("Couldn't connect to host",
                        new ConnectException("Connection refused: connect")));
    }
}
