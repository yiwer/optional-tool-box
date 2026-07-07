package cn.code91.toolbox.llm.core;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link ChatRequest} builder 语义钉住：多消息累加、options 后设覆盖先设、
 * {@link ChatRequest#user(String)} 便捷构造、空 messages 拒绝。
 */
class ChatRequestTest {

    @Test
    void userConvenienceConstructorProducesSingleUserMessageWithDefaultOptions() {
        ChatRequest request = ChatRequest.user("hello");

        assertThat(request.messages()).containsExactly(Message.user("hello"));
        assertThat(request.options()).isEqualTo(ChatOptions.defaults());
    }

    @Test
    void builderAccumulatesMessagesInCallOrder() {
        ChatRequest request = ChatRequest.builder()
                .system("你是助手")
                .user("第一句")
                .assistant("第一句回复")
                .user("第二句")
                .build();

        assertThat(request.messages()).containsExactly(
                Message.system("你是助手"),
                Message.user("第一句"),
                Message.assistant("第一句回复"),
                Message.user("第二句"));
    }

    @Test
    void builderOptionsLastCallWins() {
        ChatOptions first = new ChatOptions(0.1, null, null, null);
        ChatOptions second = new ChatOptions(0.9, 100, null, null);

        ChatRequest request = ChatRequest.builder().user("x").options(first).options(second).build();

        assertThat(request.options()).isEqualTo(second);
    }

    @Test
    void emptyMessagesRejected() {
        assertThatThrownBy(() -> new ChatRequest(java.util.List.of(), null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void nullOptionsDefaultsToAllEmptyOptions() {
        ChatRequest request = new ChatRequest(java.util.List.of(Message.user("x")), null);

        assertThat(request.options()).isEqualTo(ChatOptions.defaults());
    }

    @Test
    void messagesListIsDefensivelyCopiedAndImmutable() {
        java.util.List<Message> mutable = new java.util.ArrayList<>();
        mutable.add(Message.user("x"));
        ChatRequest request = new ChatRequest(mutable, null);
        mutable.add(Message.user("y"));

        assertThat(request.messages()).hasSize(1);
        assertThatThrownBy(() -> request.messages().add(Message.user("z")))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
