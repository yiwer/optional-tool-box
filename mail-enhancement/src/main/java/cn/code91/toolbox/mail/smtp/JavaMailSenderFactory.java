package cn.code91.toolbox.mail.smtp;

import org.springframework.mail.javamail.JavaMailSenderImpl;

import java.util.Properties;

/**
 * 按账号配置构造 {@link JavaMailSenderImpl}（04 §2.2：每账号一个实例，Boot 官方装配天然单账号，
 * 多账号正是本模块增量）。构造期校验必填项 fail-fast（00 §2 原则 8：配置错误在启动期暴露）。
 * {@code JavaMailSenderImpl} 无连接池等驻留资源，无需容器化销毁编排。
 */
public final class JavaMailSenderFactory {

    private JavaMailSenderFactory() {
    }

    /**
     * @throws IllegalStateException host 未配置时抛出（消息含 {@code toolbox.mail.accounts.<name>.host} 路径）
     */
    public static JavaMailSenderImpl create(SmtpConnectionConfig config) {
        if (config.host() == null || config.host().isBlank()) {
            throw new IllegalStateException(
                    "toolbox.mail.accounts." + config.name() + ".host 未配置：SMTP 账号必须指定服务器地址"
                            + "（配置错误在启动期暴露，不留到首次发送）");
        }
        JavaMailSenderImpl sender = new JavaMailSenderImpl();
        sender.setHost(config.host());
        if (config.port() > 0) {
            sender.setPort(config.port());
        }
        sender.setProtocol(config.protocol());
        sender.setDefaultEncoding("UTF-8");

        // Jakarta Mail 属性前缀跟随协议（mail.smtp.* / mail.smtps.*），写死 smtp 会让自定义协议失配。
        Properties props = sender.getJavaMailProperties();
        String prefix = "mail." + config.protocol() + ".";
        if (config.username() != null && !config.username().isBlank()) {
            sender.setUsername(config.username());
            sender.setPassword(config.password());
            props.setProperty(prefix + "auth", "true");
        }
        if (config.ssl()) {
            props.setProperty(prefix + "ssl.enable", "true");
        }
        if (config.timeout() != null) {
            String millis = String.valueOf(config.timeout().toMillis());
            props.setProperty(prefix + "connectiontimeout", millis);
            props.setProperty(prefix + "timeout", millis);
            props.setProperty(prefix + "writetimeout", millis);
        }
        return sender;
    }
}
