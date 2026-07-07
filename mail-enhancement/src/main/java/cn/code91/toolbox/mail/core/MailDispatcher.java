package cn.code91.toolbox.mail.core;

import cn.code91.facility.result.Result;

import java.util.concurrent.CompletableFuture;

/**
 * 邮件派发 Seam（命名避开 Spring 已占用的 {@code MailSender}，04 §4.1）。
 * 实现负责限速、沙箱、模板渲染、附件守卫、重试与回调的完整编排。
 */
public interface MailDispatcher {

    /**
     * 同步发送；一切失败以错误值返回，不抛异常。
     */
    Result<MailReceipt, MailError> send(MailMessage message);

    /**
     * 异步发送（裁定 F：虚拟线程执行器，共享不新建）；future 正常完成并携带
     * {@code Result}，不以异常完成。
     */
    CompletableFuture<Result<MailReceipt, MailError>> sendAsync(MailMessage message);
}
