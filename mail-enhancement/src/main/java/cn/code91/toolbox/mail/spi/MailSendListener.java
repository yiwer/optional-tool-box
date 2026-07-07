package cn.code91.toolbox.mail.spi;

import cn.code91.toolbox.mail.core.MailError;
import cn.code91.toolbox.mail.core.MailMessage;
import cn.code91.toolbox.mail.core.MailReceipt;

/**
 * 发送回调 SPI（L5：应用 {@code @Bean} 即被 ObjectProvider 收集，全部回调）。
 * 回调抛出的异常会被派发器吞掉并记日志，<b>不影响发送主流程与返回结果</b>。
 */
public interface MailSendListener {

    /**
     * 发送成功后回调（同步发送在调用线程执行，异步发送在虚拟线程执行）。
     */
    default void onSuccess(MailMessage message, MailReceipt receipt) {
    }

    /**
     * 发送失败后回调（含限速/沙箱/模板/附件守卫等本地拦截）。
     */
    default void onFailure(MailMessage message, MailError error) {
    }
}
