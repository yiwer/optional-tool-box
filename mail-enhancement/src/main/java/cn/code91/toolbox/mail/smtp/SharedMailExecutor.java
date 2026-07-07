package cn.code91.toolbox.mail.smtp;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 模块内共享的虚拟线程执行器（裁定 F）。facility 侧经核实<b>没有</b>可注入的
 * TaskExecutor 形态 bean（其 {@code Async} 门面自持静态执行器），故按裁定预案取
 * 模块内共享执行器，绝不逐次发送新建。
 *
 * <p>virtual-thread-per-task 执行器不驻留平台线程、不持有池资源，进程生命周期内共享
 * 一个实例即可，无需容器化销毁编排（与 storage 的客户端 bean 化销毁不同类问题）。</p>
 */
final class SharedMailExecutor {

    private static final ExecutorService INSTANCE = Executors.newVirtualThreadPerTaskExecutor();

    private SharedMailExecutor() {
    }

    static ExecutorService instance() {
        return INSTANCE;
    }
}
