package cn.code91.toolbox.mail.core;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link MailMessage} builder 语义钉住：to/cc/bcc/attach 累加、正文后设者覆盖、
 * 构建后不可变（列表不可修改）。
 */
class MailMessageTest {

    @Test
    void builderAccumulatesRecipientsAcrossCalls() {
        MailMessage message = MailMessage.builder()
                .to("a@x.com", "b@x.com")
                .to(List.of("c@x.com"))
                .cc("d@x.com")
                .bcc("e@x.com")
                .subject("s")
                .text("t")
                .build();

        assertThat(message.to()).containsExactly("a@x.com", "b@x.com", "c@x.com");
        assertThat(message.cc()).containsExactly("d@x.com");
        assertThat(message.bcc()).containsExactly("e@x.com");
    }

    @Test
    void lastBodyCallWins() {
        MailMessage message = MailMessage.builder()
                .to("a@x.com")
                .text("plain")
                .rawHtml("<b>html</b>")
                .build();

        assertThat(message.body()).isInstanceOf(MailMessage.Body.RawHtml.class);
        assertThat(((MailMessage.Body.RawHtml) message.body()).html()).isEqualTo("<b>html</b>");
    }

    @Test
    void htmlBodyCarriesTemplateNameAndVars() {
        MailMessage message = MailMessage.builder()
                .to("a@x.com")
                .html("welcome", Map.of("name", "张三"))
                .build();

        assertThat(message.body()).isInstanceOf(MailMessage.Body.Template.class);
        MailMessage.Body.Template template = (MailMessage.Body.Template) message.body();
        assertThat(template.templateName()).isEqualTo("welcome");
        assertThat(template.vars()).containsEntry("name", "张三");
    }

    @Test
    void builtMessageListsAreImmutable() {
        MailMessage message = MailMessage.builder().to("a@x.com").text("t").build();

        assertThatThrownBy(() -> message.to().add("evil@x.com"))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> message.attachments().add(Attachment.of("f.txt", new byte[0])))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void unsetOptionalFieldsAreNullOrEmpty() {
        MailMessage message = MailMessage.builder().to("a@x.com").text("t").build();

        assertThat(message.from()).isNull();
        assertThat(message.replyTo()).isNull();
        assertThat(message.subject()).isNull();
        assertThat(message.cc()).isEmpty();
        assertThat(message.bcc()).isEmpty();
        assertThat(message.attachments()).isEmpty();
    }

    @Test
    void attachmentsAccumulate() {
        MailMessage message = MailMessage.builder()
                .to("a@x.com")
                .text("t")
                .attach(Attachment.of("a.txt", new byte[]{1}))
                .attach(Attachment.of("b.txt", "text/plain", new byte[]{2}))
                .build();

        assertThat(message.attachments()).hasSize(2);
        assertThat(message.attachments().get(0).filename()).isEqualTo("a.txt");
        assertThat(message.attachments().get(0).contentType()).isNull();
        assertThat(message.attachments().get(1).contentType()).isEqualTo("text/plain");
    }

    @Test
    void nullRecipientElementFailsFastAtBuilder() {
        // 编程错误在 builder 期立即以 NPE 暴露（发送期语义校验才走错误值通道）。
        assertThatThrownBy(() -> MailMessage.builder().to((String) null))
                .isInstanceOf(NullPointerException.class);
    }
}
