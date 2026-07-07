package cn.code91.toolbox.mail.core;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 不可变邮件消息（04 §4.1），经 {@link #builder()} 构造。<b>不引用 jakarta.mail 类型</b>
 * （ArchUnit 约束：缺 starter-mail 时值对象必须可加载）。
 *
 * <p>构造期不校验收件人/正文完整性——这些属于发送期语义，由
 * {@link MailDispatcher#send} 以 {@code Err(InvalidRecipient/ConfigError)} 错误值报告，
 * 保证公开 API 零异常外抛。</p>
 */
public final class MailMessage {

    private final String from;
    private final List<String> to;
    private final List<String> cc;
    private final List<String> bcc;
    private final String replyTo;
    private final String subject;
    private final Body body;
    private final List<Attachment> attachments;

    private MailMessage(Builder builder) {
        this.from = builder.from;
        this.to = List.copyOf(builder.to);
        this.cc = List.copyOf(builder.cc);
        this.bcc = List.copyOf(builder.bcc);
        this.replyTo = builder.replyTo;
        this.subject = builder.subject;
        this.body = builder.body;
        this.attachments = List.copyOf(builder.attachments);
    }

    public static Builder builder() {
        return new Builder();
    }

    /**
     * 显式发件人；为 null 时由账号配置的 {@code from} 提供（裁定 D：spring 收编账号无
     * from 配置时必须在此显式给出，否则发送返回 {@code Err(ConfigError)}）。
     */
    public String from() {
        return from;
    }

    public List<String> to() {
        return to;
    }

    public List<String> cc() {
        return cc;
    }

    public List<String> bcc() {
        return bcc;
    }

    public String replyTo() {
        return replyTo;
    }

    public String subject() {
        return subject;
    }

    public Body body() {
        return body;
    }

    public List<Attachment> attachments() {
        return attachments;
    }

    /**
     * 正文三态（文本 / 模板 / 原始 HTML），sealed 供派发器 pattern matching 穷尽处理；
     * 为 null 表示未设置正文（发送期报 {@code ConfigError}）。
     */
    public sealed interface Body {

        /**
         * 纯文本正文。
         */
        record Text(String content) implements Body {
        }

        /**
         * 模板正文：发送期经 {@link cn.code91.toolbox.mail.spi.MailTemplateRenderer} 渲染为 HTML。
         */
        record Template(String templateName, Map<String, Object> vars) implements Body {

            public Template {
                vars = vars == null ? Map.of() : new LinkedHashMap<>(vars);
            }
        }

        /**
         * 已就绪的原始 HTML 正文（不经模板渲染）。
         */
        record RawHtml(String html) implements Body {
        }
    }

    /**
     * builder 语义（测试钉住）：to/cc/bcc/attach 多次调用为<b>累加</b>；
     * text/html/rawHtml 互斥地设置正文，<b>后设者覆盖先设者</b>。
     */
    public static final class Builder {

        private String from;
        private final List<String> to = new ArrayList<>();
        private final List<String> cc = new ArrayList<>();
        private final List<String> bcc = new ArrayList<>();
        private String replyTo;
        private String subject;
        private Body body;
        private final List<Attachment> attachments = new ArrayList<>();

        private Builder() {
        }

        public Builder from(String from) {
            this.from = Objects.requireNonNull(from, "from 不得为 null");
            return this;
        }

        public Builder to(String... addresses) {
            return to(List.of(addresses));
        }

        public Builder to(Collection<String> addresses) {
            this.to.addAll(copyOfAddresses(addresses, "to"));
            return this;
        }

        public Builder cc(String... addresses) {
            return cc(List.of(addresses));
        }

        public Builder cc(Collection<String> addresses) {
            this.cc.addAll(copyOfAddresses(addresses, "cc"));
            return this;
        }

        public Builder bcc(String... addresses) {
            return bcc(List.of(addresses));
        }

        public Builder bcc(Collection<String> addresses) {
            this.bcc.addAll(copyOfAddresses(addresses, "bcc"));
            return this;
        }

        public Builder replyTo(String replyTo) {
            this.replyTo = Objects.requireNonNull(replyTo, "replyTo 不得为 null");
            return this;
        }

        public Builder subject(String subject) {
            this.subject = Objects.requireNonNull(subject, "subject 不得为 null");
            return this;
        }

        public Builder text(String content) {
            this.body = new Body.Text(Objects.requireNonNull(content, "text 不得为 null"));
            return this;
        }

        public Builder html(String templateName, Map<String, Object> vars) {
            this.body = new Body.Template(Objects.requireNonNull(templateName, "templateName 不得为 null"), vars);
            return this;
        }

        public Builder rawHtml(String html) {
            this.body = new Body.RawHtml(Objects.requireNonNull(html, "rawHtml 不得为 null"));
            return this;
        }

        public Builder attach(Attachment attachment) {
            this.attachments.add(Objects.requireNonNull(attachment, "attachment 不得为 null"));
            return this;
        }

        public MailMessage build() {
            return new MailMessage(this);
        }

        private static List<String> copyOfAddresses(Collection<String> addresses, String field) {
            Objects.requireNonNull(addresses, field + " 不得为 null");
            List<String> copy = new ArrayList<>(addresses.size());
            for (String address : addresses) {
                copy.add(Objects.requireNonNull(address, field + " 中的地址不得为 null"));
            }
            return copy;
        }
    }
}
