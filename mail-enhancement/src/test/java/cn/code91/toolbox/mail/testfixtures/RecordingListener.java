package cn.code91.toolbox.mail.testfixtures;

import cn.code91.toolbox.mail.core.MailError;
import cn.code91.toolbox.mail.core.MailMessage;
import cn.code91.toolbox.mail.core.MailReceipt;
import cn.code91.toolbox.mail.spi.MailSendListener;

import java.util.ArrayList;
import java.util.List;

/**
 * 记录式监听器（跨测试类共享夹具）。
 */
public final class RecordingListener implements MailSendListener {

    public final List<MailReceipt> successes = new ArrayList<>();
    public final List<MailError> failures = new ArrayList<>();

    @Override
    public void onSuccess(MailMessage message, MailReceipt receipt) {
        successes.add(receipt);
    }

    @Override
    public void onFailure(MailMessage message, MailError error) {
        failures.add(error);
    }
}
