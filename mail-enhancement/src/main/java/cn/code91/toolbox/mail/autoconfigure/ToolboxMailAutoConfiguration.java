package cn.code91.toolbox.mail.autoconfigure;

import cn.code91.facility.log.LogUtil;
import cn.code91.toolbox.mail.core.DefaultMailAccountRegistry;
import cn.code91.toolbox.mail.core.MailAccountRegistry;
import cn.code91.toolbox.mail.core.MailDispatcher;
import cn.code91.toolbox.mail.guard.AttachmentGuardConfig;
import cn.code91.toolbox.mail.guard.DefaultAttachmentGuard;
import cn.code91.toolbox.mail.sandbox.SandboxPolicy;
import cn.code91.toolbox.mail.smtp.JavaMailSenderFactory;
import cn.code91.toolbox.mail.smtp.SmtpConnectionConfig;
import cn.code91.toolbox.mail.smtp.SmtpDispatchSettings;
import cn.code91.toolbox.mail.smtp.SmtpMailDispatcher;
import cn.code91.toolbox.mail.spi.AttachmentGuard;
import cn.code91.toolbox.mail.spi.MailSendListener;
import cn.code91.toolbox.mail.spi.MailTemplateRenderer;
import cn.code91.toolbox.mail.template.SimpleTemplateRenderer;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.mail.MailSenderAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.core.io.ResourceLoader;
import org.springframework.mail.javamail.JavaMailSender;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * mail-enhancement 唯一装配入口（brief 交付物）。装配条件分层（00 §3.2）：
 * L1 {@code @ConditionalOnClass}（字符串探测 MimeMessage，starter-mail 为 optional，缺类整模块
 * 不装配）；L2 总开关默认开；L4 各 bean 均 {@code @ConditionalOnMissingBean} 可被应用覆盖；
 * L5 {@code ObjectProvider} 收集 listener。声明在 Boot 官方 {@code MailSenderAutoConfiguration}
 * 之后装配，确保 adopt-spring-mail 收编时能看到官方装配出的 {@code JavaMailSender} bean（裁定 D）。
 */
@AutoConfiguration(after = MailSenderAutoConfiguration.class)
@ConditionalOnClass(name = "jakarta.mail.internet.MimeMessage")
@ConditionalOnProperty(prefix = "toolbox.mail", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(ToolboxMailProperties.class)
public class ToolboxMailAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public MailTemplateRenderer toolboxMailTemplateRenderer() {
        return new SimpleTemplateRenderer();
    }

    /**
     * 探测 Tika 所用的 {@code ClassLoader} 取容器侧（{@code ResourceLoader#getClassLoader}），
     * 使 {@code FilteredClassLoader} 条件测试对"缺 tika-core"分支真实生效（裁定 E fail-closed）。
     */
    @Bean
    @ConditionalOnMissingBean
    public AttachmentGuard toolboxMailAttachmentGuard(ToolboxMailProperties properties,
                                                      ResourceLoader resourceLoader) {
        ToolboxMailProperties.Attachment attachment = properties.attachment();
        long maxSize = attachment.maxSize() == null ? 0L : attachment.maxSize().toBytes();
        return new DefaultAttachmentGuard(
                new AttachmentGuardConfig(maxSize, attachment.blockedExtensions(), attachment.verifyMime()),
                resourceLoader.getClassLoader());
    }

    /**
     * 账号注册表装配（裁定 D）：配置账号逐个经 factory 构造（host 缺失启动期 fail-fast）；
     * {@code adopt-spring-mail=true} 且容器存在唯一消费方 {@code JavaMailSender} bean 时收编为
     * 名为 {@code spring} 的账号（显式配置的同名账号优先，不覆盖用户意图）；零账号装配空
     * registry 兜底。沙箱策略在此构造——裁定 B 的 fail-fast 因此发生在启动期。
     */
    @Bean
    @ConditionalOnMissingBean
    public MailAccountRegistry toolboxMailAccountRegistry(ToolboxMailProperties properties,
                                                          ObjectProvider<JavaMailSender> springMailSender,
                                                          ObjectProvider<MailSendListener> listeners,
                                                          MailTemplateRenderer renderer,
                                                          AttachmentGuard guard) {
        SandboxPolicy sandbox = sandboxPolicyOf(properties.sandbox());
        List<MailSendListener> listenerList = listeners.orderedStream().toList();

        Map<String, MailDispatcher> dispatchers = new LinkedHashMap<>();
        properties.accounts().forEach((name, account) ->
                dispatchers.put(name, smtpDispatcher(name, account, sandbox, renderer, guard, listenerList)));

        if (properties.adoptSpringMail() && !dispatchers.containsKey(DefaultMailAccountRegistry.SPRING_ACCOUNT)) {
            JavaMailSender adopted = springMailSender.getIfUnique();
            if (adopted != null) {
                dispatchers.put(DefaultMailAccountRegistry.SPRING_ACCOUNT, new SmtpMailDispatcher(
                        adopted, adoptedSpringSettings(), sandbox, renderer, guard, listenerList));
            }
        }
        return new DefaultMailAccountRegistry(dispatchers, properties.primary());
    }

    /**
     * 裁定 B：沙箱启用即在装配期打一条显眼 WARN（经 LogUtil）——沙箱依赖"生产不配置"纪律，
     * 误开必须在启动日志立刻可见。
     */
    private static SandboxPolicy sandboxPolicyOf(ToolboxMailProperties.Sandbox sandbox) {
        SandboxPolicy policy = new SandboxPolicy(sandbox.enabled(), sandbox.redirectTo(),
                sandbox.allowedDomains(), sandbox.allowedRecipients());
        if (policy.enabled()) {
            LogUtil.warn("【toolbox.mail 沙箱已启用】所有外发邮件将被重定向或按白名单过滤"
                            + "（redirect-to={}，allowed-domains={}，allowed-recipients={}）——生产环境请立即确认是否误开",
                    sandbox.redirectTo(), sandbox.allowedDomains(), sandbox.allowedRecipients());
        }
        return policy;
    }

    private static MailDispatcher smtpDispatcher(String name, ToolboxMailProperties.Account account,
                                                 SandboxPolicy sandbox, MailTemplateRenderer renderer,
                                                 AttachmentGuard guard, List<MailSendListener> listeners) {
        // P1 配置面不暴露 protocol/timeout（brief 属性清单），factory 走 smtp 与 Jakarta Mail 默认。
        SmtpConnectionConfig connection = new SmtpConnectionConfig(
                name, account.host(), account.port(), account.ssl(),
                account.username(), account.password(), null, null);
        SmtpDispatchSettings settings = new SmtpDispatchSettings(
                name, account.from(), account.displayName(), account.rateLimitPerMinute(),
                account.retry().maxAttempts(), account.retry().backoff());
        return new SmtpMailDispatcher(JavaMailSenderFactory.create(connection),
                settings, sandbox, renderer, guard, listeners);
    }

    /**
     * 收编账号参数（裁定 D）：无 from 配置（发送期从 MailMessage 显式取，缺失即 ConfigError）、
     * 不限速；重试沿用裁定 C 默认（3 次 / 2s 倍增）——04 §5.3 的价值主张即老项目零迁移获得
     * 沙箱/模板/重试能力。
     */
    private static SmtpDispatchSettings adoptedSpringSettings() {
        return new SmtpDispatchSettings(DefaultMailAccountRegistry.SPRING_ACCOUNT, null, null, 0, 0, null);
    }
}
