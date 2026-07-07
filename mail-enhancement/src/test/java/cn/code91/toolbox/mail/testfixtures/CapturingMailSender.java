package cn.code91.toolbox.mail.testfixtures;

import jakarta.mail.MessagingException;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 内存捕获式 {@link JavaMailSender} 桩（跨测试类共享夹具）：send 时 {@code saveChanges()}
 * （生成 Message-ID，与真实 {@code JavaMailSenderImpl} 行为一致）后入列；
 * 可注入固定异常模拟发送失败并统计尝试次数（重试断言用）。
 */
public final class CapturingMailSender implements JavaMailSender {

    private final Session session = Session.getInstance(new Properties());
    public final List<MimeMessage> sent = new ArrayList<>();
    public final AtomicInteger sendAttempts = new AtomicInteger();
    public RuntimeException failWith;

    @Override
    public MimeMessage createMimeMessage() {
        return new MimeMessage(session);
    }

    @Override
    public MimeMessage createMimeMessage(InputStream contentStream) {
        try {
            return new MimeMessage(session, contentStream);
        } catch (MessagingException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public void send(MimeMessage mimeMessage) {
        sendAttempts.incrementAndGet();
        if (failWith != null) {
            throw failWith;
        }
        try {
            mimeMessage.saveChanges();
        } catch (MessagingException e) {
            throw new IllegalStateException(e);
        }
        sent.add(mimeMessage);
    }

    @Override
    public void send(MimeMessage... mimeMessages) {
        for (MimeMessage mimeMessage : mimeMessages) {
            send(mimeMessage);
        }
    }

    @Override
    public void send(SimpleMailMessage simpleMessage) {
        throw new UnsupportedOperationException("桩不支持 SimpleMailMessage");
    }

    @Override
    public void send(SimpleMailMessage... simpleMessages) {
        throw new UnsupportedOperationException("桩不支持 SimpleMailMessage");
    }
}
