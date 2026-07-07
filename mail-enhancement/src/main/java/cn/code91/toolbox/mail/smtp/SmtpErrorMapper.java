package cn.code91.toolbox.mail.smtp;

import cn.code91.toolbox.mail.core.MailError;
import jakarta.mail.Address;
import jakarta.mail.AuthenticationFailedException;
import jakarta.mail.MessagingException;
import jakarta.mail.SendFailedException;
import jakarta.mail.internet.AddressException;
import org.springframework.mail.MailAuthenticationException;
import org.springframework.mail.MailSendException;

import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;

/**
 * jakarta.mail / Spring Mail 异常 → {@link MailError} 的收敛点（brief 交付物），单测穷尽。
 *
 * <p>映射表：{@code MailAuthenticationException}/{@code AuthenticationFailedException} →
 * {@code AuthError}；连接/超时类（含 {@code MailSendException}/{@code MessagingException}
 * 因果链内嵌套的 {@code java.net} 连接异常）→ {@code ConnectError}；
 * {@code SendFailedException}（无效地址）与 {@code AddressException}（地址语法非法，同属
 * "收件人无效"语义，实现者裁量归入同型）→ {@code InvalidRecipient}；其余 → {@code ProviderError}。</p>
 *
 * <p>类型判定顺序即代码顺序，不可随意调换：{@code AuthenticationFailedException}/
 * {@code SendFailedException}/{@code AddressException} 均为 {@code MessagingException} 子类，
 * 必须先于通用 {@code MessagingException} 分支。</p>
 */
public final class SmtpErrorMapper {

    /**
     * 因果链遍历深度上限：{@code MessagingException} 的 nextException 链可被构造成环，
     * 有界遍历保证 map 恒定返回。
     */
    private static final int MAX_CAUSE_DEPTH = 20;

    /**
     * 将发送路径捕获到的任意异常映射为 {@link MailError}，绝不外抛。
     */
    public MailError map(Exception exception) {
        if (exception instanceof MailAuthenticationException) {
            return new MailError.AuthError("SMTP 认证失败：" + rootMessage(exception));
        }
        if (exception instanceof AuthenticationFailedException) {
            return new MailError.AuthError("SMTP 认证失败：" + exception.getMessage());
        }
        if (exception instanceof SendFailedException sendFailed) {
            return invalidRecipient(sendFailed);
        }
        if (exception instanceof AddressException) {
            return new MailError.InvalidRecipient(
                    "收件人地址语法非法：" + exception.getMessage(), List.of());
        }
        if (exception instanceof MailSendException sendException) {
            return mapMailSendException(sendException);
        }
        if (hasConnectCause(exception)) {
            return new MailError.ConnectError("SMTP 连接/握手失败：" + rootMessage(exception), exception);
        }
        return new MailError.ProviderError("邮件发送发生未分类异常：" + exception.getMessage(), exception);
    }

    /**
     * {@code JavaMailSenderImpl} 的两类包装形态：逐信失败塞进 failedMessages
     * （典型为 {@code SendFailedException}）；连接期失败作为 cause 链。
     */
    private MailError mapMailSendException(MailSendException exception) {
        for (Exception nested : exception.getFailedMessages().values()) {
            if (nested instanceof SendFailedException sendFailed) {
                return invalidRecipient(sendFailed);
            }
        }
        if (hasConnectCause(exception)) {
            return new MailError.ConnectError("SMTP 连接/握手失败：" + rootMessage(exception), exception);
        }
        for (Exception nested : exception.getFailedMessages().values()) {
            if (hasConnectCause(nested)) {
                return new MailError.ConnectError("SMTP 连接/握手失败：" + rootMessage(nested), exception);
            }
        }
        return new MailError.ProviderError("邮件发送失败：" + rootMessage(exception), exception);
    }

    private static MailError invalidRecipient(SendFailedException exception) {
        List<String> invalid = new ArrayList<>();
        Address[] addresses = exception.getInvalidAddresses();
        if (addresses != null) {
            for (Address address : addresses) {
                invalid.add(String.valueOf(address));
            }
        }
        return new MailError.InvalidRecipient(
                "收件人无效或被服务器拒绝：" + exception.getMessage()
                        + (invalid.isEmpty() ? "" : "（无效地址：" + invalid + "）"),
                invalid);
    }

    /**
     * 有界遍历因果链判定连接类异常；jakarta {@code MessagingException} 已把 nextException
     * 覆写进 {@code getCause()}，标准 cause 遍历即可覆盖其链式形态。
     */
    private static boolean hasConnectCause(Throwable root) {
        Throwable current = root;
        int depth = 0;
        while (current != null && depth < MAX_CAUSE_DEPTH) {
            if (isConnectType(current)) {
                return true;
            }
            current = current.getCause();
            depth++;
        }
        return false;
    }

    private static boolean isConnectType(Throwable throwable) {
        return throwable instanceof ConnectException
                || throwable instanceof SocketTimeoutException
                || throwable instanceof UnknownHostException
                || throwable instanceof NoRouteToHostException
                || throwable instanceof SocketException;
    }

    /**
     * 取因果链上最深一层的消息（最贴近根因，如 "Connection refused"），全链皆空时回退顶层类名。
     */
    private static String rootMessage(Throwable root) {
        Throwable current = root;
        String message = root.getMessage();
        int depth = 0;
        while (current != null && depth < MAX_CAUSE_DEPTH) {
            if (current.getMessage() != null && !current.getMessage().isBlank()) {
                message = current.getMessage();
            }
            current = current.getCause();
            depth++;
        }
        return message == null ? root.getClass().getSimpleName() : message;
    }
}
