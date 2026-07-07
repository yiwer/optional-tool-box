package cn.code91.toolbox.mail.smtp;

import cn.code91.facility.log.LogUtil;
import cn.code91.facility.result.Result;
import cn.code91.toolbox.mail.core.Attachment;
import cn.code91.toolbox.mail.core.MailDispatcher;
import cn.code91.toolbox.mail.core.MailError;
import cn.code91.toolbox.mail.core.MailMessage;
import cn.code91.toolbox.mail.core.MailReceipt;
import cn.code91.toolbox.mail.sandbox.SandboxDecision;
import cn.code91.toolbox.mail.sandbox.SandboxPolicy;
import cn.code91.toolbox.mail.spi.AttachmentGuard;
import cn.code91.toolbox.mail.spi.MailSendListener;
import cn.code91.toolbox.mail.spi.MailTemplateRenderer;
import jakarta.mail.internet.MimeMessage;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * SMTP 派发器（brief 中唯一复杂类）。{@link #send} 编排固定顺序：
 * 限速 → 收件人校验 → 沙箱 → 发件人解析 → 模板渲染 → 附件守卫 → MimeMessage 构造 →
 * 发送（仅 {@code ConnectError} 指数退避重试，裁定 C）→ 回执 → 回调；各环节独立小方法可单测。
 * 一切失败以 {@link MailError} 错误值返回，公开 API 零异常外抛。
 */
public final class SmtpMailDispatcher implements MailDispatcher {

    /**
     * 限速门 Seam（包内可见，测试注入假门以演练 RateLimited 分支——facility 门面在容器
     * 无 RateLimiter bean 时降级放行，单测环境命不中限速）。默认实现 {@link FacilityRateLimitGate}。
     */
    @FunctionalInterface
    interface RateLimitGate {

        /**
         * @return null 表示放行；非 null 为命中限速时的建议等待时长（不阻塞等待，裁定 C）
         */
        Duration acquireOrSuggestWait(String accountName, int permitsPerMinute);
    }

    /**
     * 退避睡眠 Seam（包内可见，测试注入记录器以断言重试次数与倍增间隔而不真实等待）。
     */
    @FunctionalInterface
    interface Sleeper {

        void sleep(Duration duration) throws InterruptedException;
    }

    private final JavaMailSender mailSender;
    private final SmtpDispatchSettings settings;
    private final SandboxPolicy sandboxPolicy;
    private final MailTemplateRenderer renderer;
    private final AttachmentGuard guard;
    private final List<MailSendListener> listeners;
    private final SmtpErrorMapper errorMapper = new SmtpErrorMapper();
    private final RateLimitGate rateLimitGate;
    private final Sleeper sleeper;

    public SmtpMailDispatcher(JavaMailSender mailSender, SmtpDispatchSettings settings,
                              SandboxPolicy sandboxPolicy, MailTemplateRenderer renderer,
                              AttachmentGuard guard, List<MailSendListener> listeners) {
        this(mailSender, settings, sandboxPolicy, renderer, guard, listeners,
                new FacilityRateLimitGate(), duration -> Thread.sleep(duration.toMillis()));
    }

    SmtpMailDispatcher(JavaMailSender mailSender, SmtpDispatchSettings settings,
                       SandboxPolicy sandboxPolicy, MailTemplateRenderer renderer,
                       AttachmentGuard guard, List<MailSendListener> listeners,
                       RateLimitGate rateLimitGate, Sleeper sleeper) {
        this.mailSender = mailSender;
        this.settings = settings;
        this.sandboxPolicy = sandboxPolicy;
        this.renderer = renderer;
        this.guard = guard;
        this.listeners = listeners == null ? List.of() : List.copyOf(listeners);
        this.rateLimitGate = rateLimitGate;
        this.sleeper = sleeper;
    }

    /**
     * 重定向时保留原收件人的邮件头（裁定 B）；值为逗号连接的原地址列表（单头，便于断言与解析）。
     */
    static final String HEADER_ORIGINAL_TO = "X-Original-To";

    @Override
    public Result<MailReceipt, MailError> send(MailMessage message) {
        Result<MailReceipt, MailError> result = doSend(message);
        notifyListeners(message, result);
        return result;
    }

    @Override
    public CompletableFuture<Result<MailReceipt, MailError>> sendAsync(MailMessage message) {
        return CompletableFuture.supplyAsync(() -> send(message), SharedMailExecutor.instance());
    }

    private Result<MailReceipt, MailError> doSend(MailMessage message) {
        MailError rateLimited = checkRateLimit();
        if (rateLimited != null) {
            return Result.err(rateLimited);
        }

        List<String> allRecipients = allRecipientsOf(message);
        if (allRecipients.isEmpty()) {
            return Result.err(new MailError.InvalidRecipient(
                    "邮件未指定任何收件人（to/cc/bcc 皆空），无法发送", List.of()));
        }

        SandboxDecision decision = sandboxPolicy.decide(allRecipients);
        if (decision instanceof SandboxDecision.Blocked blocked) {
            return Result.err(new MailError.SandboxBlocked(
                    "沙箱拦截：收件人 " + blocked.disallowed() + " 未命中白名单"
                            + "（toolbox.mail.sandbox.allowed-domains / allowed-recipients），整封邮件不发送",
                    blocked.originalRecipients()));
        }

        String from = resolveFrom(message);
        if (from == null) {
            return Result.err(new MailError.ConfigError(
                    "账号 \"" + settings.accountName() + "\" 未配置 from 且 MailMessage 未显式指定发件人；"
                            + "请配置 toolbox.mail.accounts." + settings.accountName()
                            + ".from，或在消息上调用 MailMessage.builder().from(...)"));
        }

        Result<BodyContent, MailError> body = resolveBody(message);
        if (body.isErr()) {
            return Result.err(body.getErr());
        }

        for (Attachment attachment : message.attachments()) {
            Result<Void, MailError> checked = guard.check(attachment);
            if (checked.isErr()) {
                return Result.err(checked.getErr());
            }
        }

        MimeMessage mime;
        try {
            mime = buildMimeMessage(message, from, body.get(), decision);
        } catch (Exception e) {
            return Result.err(errorMapper.map(e));
        }
        return sendWithRetry(mime);
    }

    /**
     * 限速为账号级（裁定 C）：{@code rate-limit-per-minute <= 0} 完全不触碰限速门，
     * 命中时携带建议等待直返，不阻塞等待。
     */
    private MailError checkRateLimit() {
        int perMinute = settings.rateLimitPerMinute();
        if (perMinute <= 0) {
            return null;
        }
        Duration wait = rateLimitGate.acquireOrSuggestWait(settings.accountName(), perMinute);
        if (wait == null) {
            return null;
        }
        return new MailError.RateLimited(
                "账号 \"" + settings.accountName() + "\" 命中本地发送限速（rate-limit-per-minute="
                        + perMinute + "），建议等待 " + wait.toMillis() + "ms 后重试", wait);
    }

    private static List<String> allRecipientsOf(MailMessage message) {
        List<String> all = new ArrayList<>(message.to());
        all.addAll(message.cc());
        all.addAll(message.bcc());
        return all;
    }

    /**
     * 发件人解析：消息显式 from 优先于账号配置 from（更具体者胜）；两处皆缺由调用方报
     * {@code ConfigError}（裁定 D：spring 收编账号无 from 配置的场景走的就是这条路径）。
     */
    private String resolveFrom(MailMessage message) {
        if (message.from() != null && !message.from().isBlank()) {
            return message.from();
        }
        if (settings.from() != null && !settings.from().isBlank()) {
            return settings.from();
        }
        return null;
    }

    private Result<BodyContent, MailError> resolveBody(MailMessage message) {
        return switch (message.body()) {
            case null -> Result.err(new MailError.ConfigError(
                    "邮件未设置正文：请调用 text(...) / html(templateName, vars) / rawHtml(...) 三者之一"));
            case MailMessage.Body.Text text -> Result.ok(new BodyContent(text.content(), false));
            case MailMessage.Body.RawHtml rawHtml -> Result.ok(new BodyContent(rawHtml.html(), true));
            case MailMessage.Body.Template template -> renderer
                    .render(template.templateName(), template.vars(), null)
                    .map(html -> new BodyContent(html, true));
        };
    }

    private MimeMessage buildMimeMessage(MailMessage message, String from, BodyContent body,
                                         SandboxDecision decision) throws Exception {
        MimeMessage mime = mailSender.createMimeMessage();
        MimeMessageHelper helper = new MimeMessageHelper(
                mime, !message.attachments().isEmpty(), StandardCharsets.UTF_8.name());
        if (settings.displayName() != null && !settings.displayName().isBlank()) {
            helper.setFrom(from, settings.displayName());
        } else {
            helper.setFrom(from);
        }
        applyRecipients(helper, mime, message, decision);
        if (message.replyTo() != null) {
            helper.setReplyTo(message.replyTo());
        }
        helper.setSubject(message.subject() == null ? "" : message.subject());
        helper.setText(body.content(), body.html());
        for (Attachment attachment : message.attachments()) {
            if (attachment.contentType() == null || attachment.contentType().isBlank()) {
                helper.addAttachment(attachment.filename(), new ByteArrayResource(attachment.bytes()));
            } else {
                helper.addAttachment(attachment.filename(),
                        new ByteArrayResource(attachment.bytes()), attachment.contentType());
            }
        }
        return mime;
    }

    /**
     * redirect 语义（裁定 B）：to/cc/bcc <b>全部</b>折叠为单一 redirect 地址（cc/bcc 不保留，
     * 防止任何真实地址从抄送口漏出），原收件人写入 {@code X-Original-To} 头并记日志
     * （LogUtil 写前自动经 MaskUtil 脱敏，收件人邮箱不明文落日志）。
     */
    private static void applyRecipients(MimeMessageHelper helper, MimeMessage mime,
                                        MailMessage message, SandboxDecision decision) throws Exception {
        if (decision instanceof SandboxDecision.Redirect redirect) {
            helper.setTo(redirect.redirectTo());
            mime.setHeader(HEADER_ORIGINAL_TO, String.join(", ", redirect.originalRecipients()));
            LogUtil.warn("toolbox.mail 沙箱重定向：原收件人 {} 的邮件改发 {}（原地址已写入 X-Original-To 头）",
                    redirect.originalRecipients(), redirect.redirectTo());
            return;
        }
        if (!message.to().isEmpty()) {
            helper.setTo(message.to().toArray(String[]::new));
        }
        if (!message.cc().isEmpty()) {
            helper.setCc(message.cc().toArray(String[]::new));
        }
        if (!message.bcc().isEmpty()) {
            helper.setBcc(message.bcc().toArray(String[]::new));
        }
    }

    /**
     * 重试语义（裁定 C）：仅 {@code ConnectError} 重试，同步阻塞式指数退避
     * （第 n 次重试前等待 {@code backoff * 2^(n-1)}）；其余错误直返；退避期间被中断则
     * 恢复中断位并放弃剩余重试（携带最近一次错误返回）。
     */
    private Result<MailReceipt, MailError> sendWithRetry(MimeMessage mime) {
        int maxAttempts = settings.retryMaxAttempts();
        int attempt = 0;
        while (true) {
            attempt++;
            try {
                mailSender.send(mime);
                return Result.ok(new MailReceipt(messageIdOf(mime), settings.accountName(), OffsetDateTime.now()));
            } catch (Exception e) {
                MailError mapped = errorMapper.map(e);
                if (!(mapped instanceof MailError.ConnectError) || attempt >= maxAttempts) {
                    return Result.err(mapped);
                }
                Duration delay = settings.retryBackoff().multipliedBy(1L << (attempt - 1));
                LogUtil.warn("账号 \"{}\" 第 {}/{} 次发送遇连接失败，{}ms 后重试：{}",
                        settings.accountName(), attempt, maxAttempts, delay.toMillis(), mapped.message());
                try {
                    sleeper.sleep(delay);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return Result.err(mapped);
                }
            }
        }
    }

    /**
     * Message-ID 在 {@code JavaMailSenderImpl.send} 内部 {@code saveChanges()} 时生成；
     * 读取失败不足以把一次已成功的发送改判为失败，回退空串。
     */
    private static String messageIdOf(MimeMessage mime) {
        try {
            String messageId = mime.getMessageID();
            return messageId == null ? "" : messageId;
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * 全量回调（brief：ObjectProvider 收集的全部 listener 都通知）；单个回调异常吞掉并记日志，
     * 既不影响发送结果，也不影响后续 listener。
     */
    private void notifyListeners(MailMessage message, Result<MailReceipt, MailError> result) {
        for (MailSendListener listener : listeners) {
            try {
                if (result.isOk()) {
                    listener.onSuccess(message, result.get());
                } else {
                    listener.onFailure(message, result.getErr());
                }
            } catch (Exception e) {
                LogUtil.warn("MailSendListener {} 回调异常已忽略（不影响发送主流程）", e,
                        listener.getClass().getName());
            }
        }
    }

    /**
     * 正文解析产物（内容 + 是否 HTML）。
     */
    private record BodyContent(String content, boolean html) {
    }
}
