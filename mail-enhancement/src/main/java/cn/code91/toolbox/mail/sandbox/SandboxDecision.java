package cn.code91.toolbox.mail.sandbox;

import java.util.List;

/**
 * 沙箱裁决三态（04 §4.3 决策顺序：redirect-to 优先，其次白名单，未命中拦截），
 * sealed 供派发器 pattern matching 穷尽处理。
 */
public sealed interface SandboxDecision {

    /**
     * 直通：沙箱关闭，或全部收件人命中白名单。
     */
    record Pass() implements SandboxDecision {
    }

    /**
     * 整体重定向：全部收件人（to/cc/bcc）替换为 {@code redirectTo}，原收件人写入
     * {@code X-Original-To} 邮件头并记日志（裁定 B）。
     *
     * @param redirectTo         重定向目标邮箱
     * @param originalRecipients 被替换前的全部原收件人
     */
    record Redirect(String redirectTo, List<String> originalRecipients) implements SandboxDecision {

        public Redirect {
            originalRecipients = List.copyOf(originalRecipients);
        }
    }

    /**
     * 拦截：存在未命中白名单的收件人（部分未命中即整体拦截，fail-closed）。
     *
     * @param originalRecipients 全部原收件人（对应 {@code SandboxBlocked.originalRecipients}）
     * @param disallowed         其中未命中白名单的收件人（便于直接定位违规地址）
     */
    record Blocked(List<String> originalRecipients, List<String> disallowed) implements SandboxDecision {

        public Blocked {
            originalRecipients = List.copyOf(originalRecipients);
            disallowed = List.copyOf(disallowed);
        }
    }
}
