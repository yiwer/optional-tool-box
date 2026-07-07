package cn.code91.toolbox.mail.smtp;

import java.time.Duration;

/**
 * {@link JavaMailSenderFactory} 的连接参数（smtp 包不得依赖 autoconfigure，由装配层转换后传入）。
 *
 * @param name     逻辑账号名（仅用于 fail-fast 报错时指出配置路径）
 * @param host     SMTP 服务器地址（必填，构造期校验）
 * @param port     端口；{@code <= 0} 时交由 Jakarta Mail 按协议默认（smtp=25）
 * @param ssl      是否启用 SSL（设置 {@code mail.<protocol>.ssl.enable=true}）
 * @param username 认证用户名；为空表示匿名 SMTP（不设置 {@code auth} 属性）
 * @param password 认证密码（<b>不得出现在任何日志/异常消息中</b>）
 * @param protocol 传输协议，null/空回退 {@code smtp}（P1 配置面暂不暴露，API 供编程使用）
 * @param timeout  连接/读/写三类超时的统一值，null 表示走 Jakarta Mail 默认（无限）
 */
public record SmtpConnectionConfig(String name, String host, int port, boolean ssl,
                                   String username, String password, String protocol, Duration timeout) {

    public SmtpConnectionConfig {
        if (protocol == null || protocol.isBlank()) {
            protocol = "smtp";
        }
    }
}
