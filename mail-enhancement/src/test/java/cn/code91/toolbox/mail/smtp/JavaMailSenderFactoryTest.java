package cn.code91.toolbox.mail.smtp;

import org.junit.jupiter.api.Test;
import org.springframework.mail.javamail.JavaMailSenderImpl;

import java.time.Duration;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link JavaMailSenderFactory}：构造期必填项 fail-fast + host/port/ssl/auth/timeout 接线。
 */
class JavaMailSenderFactoryTest {

    @Test
    void missingHostFailsFastWithConfigPath() {
        SmtpConnectionConfig config =
                new SmtpConnectionConfig("notice", null, 0, false, null, null, null, null);

        assertThatThrownBy(() -> JavaMailSenderFactory.create(config))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("toolbox.mail.accounts.notice.host");
    }

    @Test
    void blankHostFailsFastAsWell() {
        SmtpConnectionConfig config =
                new SmtpConnectionConfig("alert", "  ", 0, false, null, null, null, null);

        assertThatThrownBy(() -> JavaMailSenderFactory.create(config))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("alert");
    }

    @Test
    void fullConfigIsWiredIntoSenderAndJavaMailProperties() {
        SmtpConnectionConfig config = new SmtpConnectionConfig(
                "notice", "smtp.exmail.qq.com", 465, true,
                "notice@code91.cn", "secret", null, Duration.ofSeconds(5));

        JavaMailSenderImpl sender = JavaMailSenderFactory.create(config);

        assertThat(sender.getHost()).isEqualTo("smtp.exmail.qq.com");
        assertThat(sender.getPort()).isEqualTo(465);
        assertThat(sender.getUsername()).isEqualTo("notice@code91.cn");
        assertThat(sender.getPassword()).isEqualTo("secret");
        assertThat(sender.getProtocol()).isEqualTo("smtp");
        assertThat(sender.getDefaultEncoding()).isEqualTo("UTF-8");
        Properties props = sender.getJavaMailProperties();
        assertThat(props.getProperty("mail.smtp.auth")).isEqualTo("true");
        assertThat(props.getProperty("mail.smtp.ssl.enable")).isEqualTo("true");
        assertThat(props.getProperty("mail.smtp.connectiontimeout")).isEqualTo("5000");
        assertThat(props.getProperty("mail.smtp.timeout")).isEqualTo("5000");
        assertThat(props.getProperty("mail.smtp.writetimeout")).isEqualTo("5000");
    }

    @Test
    void anonymousAccountLeavesAuthAndCredentialsUnset() {
        SmtpConnectionConfig config =
                new SmtpConnectionConfig("relay", "localhost", 25, false, null, null, null, null);

        JavaMailSenderImpl sender = JavaMailSenderFactory.create(config);

        assertThat(sender.getUsername()).isNull();
        assertThat(sender.getJavaMailProperties().getProperty("mail.smtp.auth")).isNull();
        assertThat(sender.getJavaMailProperties().getProperty("mail.smtp.ssl.enable")).isNull();
    }

    @Test
    void unsetPortFallsBackToJakartaMailProtocolDefault() {
        SmtpConnectionConfig config =
                new SmtpConnectionConfig("relay", "localhost", 0, false, null, null, null, null);

        JavaMailSenderImpl sender = JavaMailSenderFactory.create(config);

        // JavaMailSenderImpl 的 -1 哨兵表示"按协议默认端口"（smtp=25）。
        assertThat(sender.getPort()).isEqualTo(-1);
    }

    @Test
    void unsetTimeoutLeavesJakartaMailDefaults() {
        SmtpConnectionConfig config =
                new SmtpConnectionConfig("relay", "localhost", 25, false, null, null, null, null);

        JavaMailSenderImpl sender = JavaMailSenderFactory.create(config);

        assertThat(sender.getJavaMailProperties().getProperty("mail.smtp.connectiontimeout")).isNull();
    }

    @Test
    void connectionConfigToStringMasksPassword() {
        // 敏感字段不落日志：record 默认 toString 会明文输出组件，密码必须脱敏。
        SmtpConnectionConfig config = new SmtpConnectionConfig(
                "notice", "smtp.exmail.qq.com", 465, true,
                "notice@code91.cn", "super-secret-password", null, null);

        assertThat(config.toString())
                .doesNotContain("super-secret-password")
                .contains("notice@code91.cn");
    }

    @Test
    void customProtocolDrivesPropertyPrefix() {
        SmtpConnectionConfig config = new SmtpConnectionConfig(
                "secure", "localhost", 465, true, "u", "p", "smtps", null);

        JavaMailSenderImpl sender = JavaMailSenderFactory.create(config);

        assertThat(sender.getProtocol()).isEqualTo("smtps");
        assertThat(sender.getJavaMailProperties().getProperty("mail.smtps.auth")).isEqualTo("true");
        assertThat(sender.getJavaMailProperties().getProperty("mail.smtps.ssl.enable")).isEqualTo("true");
    }
}
