package cn.code91.toolbox.mail.smtp;

import cn.code91.toolbox.mail.core.MailError;
import jakarta.mail.Address;
import jakarta.mail.AuthenticationFailedException;
import jakarta.mail.MessagingException;
import jakarta.mail.SendFailedException;
import jakarta.mail.internet.AddressException;
import jakarta.mail.internet.InternetAddress;
import org.junit.jupiter.api.Test;
import org.springframework.mail.MailAuthenticationException;
import org.springframework.mail.MailParseException;
import org.springframework.mail.MailSendException;

import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link SmtpErrorMapper} 映射表穷尽单测（brief：构造真实 jakarta.mail 异常实例）。
 * 映射表：认证类→AuthError；连接/超时类（含 MailSendException 内嵌套）→ConnectError；
 * SendFailedException/AddressException→InvalidRecipient；其余→ProviderError。
 */
class SmtpErrorMapperTest {

    private final SmtpErrorMapper mapper = new SmtpErrorMapper();

    @Test
    void mapsAuthenticationFailedExceptionToAuthError() {
        AuthenticationFailedException exception =
                new AuthenticationFailedException("535 5.7.8 authentication failed");

        MailError mapped = mapper.map(exception);

        assertThat(mapped).isInstanceOf(MailError.AuthError.class);
        assertThat(mapped.message()).contains("535");
    }

    @Test
    void mapsSpringMailAuthenticationExceptionToAuthError() {
        // JavaMailSenderImpl 连接期捕获 AuthenticationFailedException 后固定包装为该类型。
        MailAuthenticationException exception =
                new MailAuthenticationException(new AuthenticationFailedException("535 auth rejected"));

        MailError mapped = mapper.map(exception);

        assertThat(mapped).isInstanceOf(MailError.AuthError.class);
        assertThat(mapped.message()).contains("535");
    }

    @Test
    void mapsSendFailedExceptionToInvalidRecipientWithAddresses() throws Exception {
        Address[] invalid = {new InternetAddress("rejected@nowhere.invalid")};
        SendFailedException exception =
                new SendFailedException("550 user unknown", null, null, null, invalid);

        MailError mapped = mapper.map(exception);

        assertThat(mapped).isInstanceOf(MailError.InvalidRecipient.class);
        assertThat(((MailError.InvalidRecipient) mapped).invalidAddresses())
                .containsExactly("rejected@nowhere.invalid");
    }

    @Test
    void mapsSendFailedExceptionWithoutAddressArraysToInvalidRecipientWithEmptyList() {
        SendFailedException exception = new SendFailedException("550 rejected");

        MailError mapped = mapper.map(exception);

        assertThat(mapped).isInstanceOf(MailError.InvalidRecipient.class);
        assertThat(((MailError.InvalidRecipient) mapped).invalidAddresses()).isEmpty();
    }

    @Test
    void mapsAddressExceptionToInvalidRecipient() {
        AddressException exception = new AddressException("Domain contains illegal character", "bad@@x", 4);

        MailError mapped = mapper.map(exception);

        assertThat(mapped).isInstanceOf(MailError.InvalidRecipient.class);
    }

    @Test
    void mapsMailSendExceptionWrappingSendFailedToInvalidRecipient() throws Exception {
        // JavaMailSenderImpl 逐信失败塞进 failedMessages（key 为 MimeMessage，此处用占位对象即可）。
        Address[] invalid = {new InternetAddress("gone@nowhere.invalid")};
        MailSendException exception = new MailSendException(
                Map.of(new Object(), new SendFailedException("550 unknown", null, null, null, invalid)));

        MailError mapped = mapper.map(exception);

        assertThat(mapped).isInstanceOf(MailError.InvalidRecipient.class);
        assertThat(((MailError.InvalidRecipient) mapped).invalidAddresses())
                .containsExactly("gone@nowhere.invalid");
    }

    @Test
    void mapsMailSendExceptionWithNestedConnectExceptionToConnectError() {
        // 连接拒绝的真实形态：MailSendException → MessagingException → ConnectException。
        MailSendException exception = new MailSendException("Mail server connection failed",
                new MessagingException("Couldn't connect to host, port: localhost, 1",
                        new ConnectException("Connection refused: connect")));

        MailError mapped = mapper.map(exception);

        assertThat(mapped).isInstanceOf(MailError.ConnectError.class);
        assertThat(((MailError.ConnectError) mapped).cause()).isSameAs(exception);
    }

    @Test
    void mapsMailSendExceptionWithFailedMessageConnectCauseToConnectError() {
        MailSendException exception = new MailSendException(
                Map.of(new Object(), new MessagingException("timeout",
                        new SocketTimeoutException("Read timed out"))));

        MailError mapped = mapper.map(exception);

        assertThat(mapped).isInstanceOf(MailError.ConnectError.class);
    }

    @Test
    void mapsMessagingExceptionWithTimeoutCauseToConnectError() {
        MessagingException exception =
                new MessagingException("could not connect", new SocketTimeoutException("connect timed out"));

        MailError mapped = mapper.map(exception);

        assertThat(mapped).isInstanceOf(MailError.ConnectError.class);
    }

    @Test
    void mapsBareConnectExceptionToConnectError() {
        MailError mapped = mapper.map(new ConnectException("Connection refused"));

        assertThat(mapped).isInstanceOf(MailError.ConnectError.class);
    }

    @Test
    void mapsUnknownHostExceptionToConnectError() {
        MailError mapped = mapper.map(new UnknownHostException("smtp.nowhere.invalid"));

        assertThat(mapped).isInstanceOf(MailError.ConnectError.class);
    }

    @Test
    void mapsPlainMessagingExceptionToProviderError() {
        MessagingException exception = new MessagingException("452 insufficient system storage");

        MailError mapped = mapper.map(exception);

        assertThat(mapped).isInstanceOf(MailError.ProviderError.class);
        assertThat(((MailError.ProviderError) mapped).cause()).isSameAs(exception);
    }

    @Test
    void mapsMailSendExceptionWithoutRecognizableCauseToProviderError() {
        MailSendException exception = new MailSendException("failure when sending");

        MailError mapped = mapper.map(exception);

        assertThat(mapped).isInstanceOf(MailError.ProviderError.class);
    }

    @Test
    void mapsMailParseExceptionToProviderError() {
        MailParseException exception = new MailParseException("malformed mime content");

        MailError mapped = mapper.map(exception);

        assertThat(mapped).isInstanceOf(MailError.ProviderError.class);
    }

    @Test
    void mapsArbitraryRuntimeExceptionToProviderError() {
        IllegalStateException exception = new IllegalStateException("unexpected");

        MailError mapped = mapper.map(exception);

        assertThat(mapped).isInstanceOf(MailError.ProviderError.class);
        assertThat(((MailError.ProviderError) mapped).cause()).isSameAs(exception);
    }

    @Test
    void survivesCyclicCauseChainsWithoutInfiniteLoop() {
        // 因果链自环护栏：MessagingException.setNextException 链可被使用方构造成环，
        // 深度上限保证 map 恒定返回而非挂死。
        MessagingException first = new MessagingException("a");
        MessagingException second = new MessagingException("b");
        first.setNextException(second);
        second.setNextException(first);

        MailError mapped = mapper.map(first);

        assertThat(mapped).isInstanceOf(MailError.ProviderError.class);
    }
}
