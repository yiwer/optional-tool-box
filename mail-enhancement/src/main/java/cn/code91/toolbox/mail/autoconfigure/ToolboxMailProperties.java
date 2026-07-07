package cn.code91.toolbox.mail.autoconfigure;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.NestedConfigurationProperty;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.util.unit.DataSize;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * {@code toolbox.mail.*} 配置（04 §6 全字段）。
 *
 * @param enabled         模块总开关（实际装配与否由 {@link ToolboxMailAutoConfiguration} 类上的
 *                        {@code @ConditionalOnProperty(matchIfMissing = true)} 决定，本字段仅供
 *                        配置元数据展示，同 compare/storage 先例）
 * @param primary         默认账号名（裁定 D 三级解析的第一级；未配置时依次回退唯一账号、spring）
 * @param adoptSpringMail 是否收编消费方自有 {@code JavaMailSender} bean 为名为 spring 的账号
 *                        （默认 true，04 §5.3 共存策略）
 * @param accounts        逻辑账号名 → SMTP 账号配置
 * @param attachment      附件守卫参数
 * @param sandbox         沙箱参数（生产恒不配置；dev/test profile 打开，裁定 B）
 */
@ConfigurationProperties(prefix = "toolbox.mail")
public record ToolboxMailProperties(
        boolean enabled,
        String primary,
        @DefaultValue("true") boolean adoptSpringMail,
        Map<String, Account> accounts,
        @NestedConfigurationProperty Attachment attachment,
        @NestedConfigurationProperty Sandbox sandbox) {

    public ToolboxMailProperties {
        accounts = accounts == null ? Map.of() : Map.copyOf(accounts);
        if (attachment == null) {
            attachment = Attachment.defaults();
        }
        if (sandbox == null) {
            sandbox = Sandbox.defaults();
        }
    }

    /**
     * 单个 SMTP 账号（04 §6）。
     *
     * @param host               SMTP 服务器地址（必填，装配期 fail-fast）
     * @param port               端口；未配置（0）按协议默认
     * @param ssl                是否启用 SSL
     * @param username           认证用户名；未配置为匿名 SMTP
     * @param password           认证密码（<b>强制 {@code ${ENV_VAR}} 占位符约定</b>，风险 R6；
     *                           不落任何日志）
     * @param from               默认发件人；未配置时发送期从 MailMessage 显式取（裁定 D）
     * @param displayName        发件人显示名
     * @param rateLimitPerMinute 每分钟发送上限，0=不限速（裁定 C）
     * @param retry              连接类失败的重试参数（裁定 C）
     */
    public record Account(String host, int port, boolean ssl, String username, String password,
                          String from, String displayName, int rateLimitPerMinute,
                          @NestedConfigurationProperty Retry retry) {

        public Account {
            if (retry == null) {
                retry = Retry.defaults();
            }
        }
    }

    /**
     * 重试参数（仅 {@code ConnectError} 生效，指数倍增，裁定 C）。
     *
     * @param maxAttempts 总尝试次数上限（含首次）；非正回退默认 3
     * @param backoff     首次重试退避基值；未配置回退默认 2s
     */
    public record Retry(int maxAttempts, Duration backoff) {

        public Retry {
            if (maxAttempts <= 0) {
                maxAttempts = 3;
            }
            if (backoff == null || backoff.isNegative() || backoff.isZero()) {
                backoff = Duration.ofSeconds(2);
            }
        }

        static Retry defaults() {
            return new Retry(3, Duration.ofSeconds(2));
        }
    }

    /**
     * 附件守卫参数（裁定 E）。
     *
     * @param maxSize           最大允许大小；未配置不限制
     * @param blockedExtensions 配置叠加的扩展名黑名单（与 facility 内置危险扩展名并集）
     * @param verifyMime        是否启用魔数嗅探与声明 Content-Type 比对（默认 <b>false</b>，裁定 E）
     */
    public record Attachment(DataSize maxSize, Set<String> blockedExtensions, boolean verifyMime) {

        public Attachment {
            blockedExtensions = blockedExtensions == null ? Set.of() : Set.copyOf(blockedExtensions);
        }

        static Attachment defaults() {
            return new Attachment(null, Set.of(), false);
        }
    }

    /**
     * 沙箱参数（裁定 B：enabled=true 时 redirect-to 与白名单二选一必配，否则启动 fail-fast）。
     *
     * @param enabled           是否启用沙箱（默认 false；有外部副作用的子开关默认关，00 §3.2）
     * @param redirectTo        方式一：全部收件人整体重定向到该测试邮箱（优先于白名单）
     * @param allowedDomains    方式二：放行的收件人域名白名单
     * @param allowedRecipients 方式二：放行的完整收件人地址白名单（与域名白名单并集）
     */
    public record Sandbox(boolean enabled, String redirectTo,
                          List<String> allowedDomains, List<String> allowedRecipients) {

        public Sandbox {
            allowedDomains = allowedDomains == null ? List.of() : List.copyOf(allowedDomains);
            allowedRecipients = allowedRecipients == null ? List.of() : List.copyOf(allowedRecipients);
        }

        static Sandbox defaults() {
            return new Sandbox(false, null, List.of(), List.of());
        }
    }
}
