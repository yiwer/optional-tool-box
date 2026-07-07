package cn.code91.toolbox.mail.spi;

import cn.code91.facility.result.Result;
import cn.code91.toolbox.mail.core.Attachment;
import cn.code91.toolbox.mail.core.MailError;

/**
 * 附件守卫 Seam（L4，默认实现串联多项检查，裁定 E）。派发器在构造 MimeMessage 前
 * 对每个附件调用；任何一项违规即 {@code Err(AttachmentRejected)}，消息说明被拦原因。
 */
public interface AttachmentGuard {

    Result<Void, MailError> check(Attachment attachment);
}
