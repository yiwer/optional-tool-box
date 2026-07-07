package cn.code91.toolbox.mail.core;

import java.time.OffsetDateTime;

/**
 * 发送成功回执。
 *
 * @param messageId 邮件 Message-ID（由邮件引擎生成；引擎未提供时为空串）
 * @param account   实际执行发送的逻辑账号名
 * @param sentAt    发送完成时间
 */
public record MailReceipt(String messageId, String account, OffsetDateTime sentAt) {
}
