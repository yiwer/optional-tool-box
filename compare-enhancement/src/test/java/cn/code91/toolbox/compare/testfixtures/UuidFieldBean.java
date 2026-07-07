package cn.code91.toolbox.compare.testfixtures;

import java.util.UUID;

/**
 * 含 {@code UUID} 字段的测试夹具（I2 回归）：{@code UUID} 不在叶子类型表内，
 * 引擎应把它当普通对象递归展开字段——但 {@code UUID} 内部字段反射访问失败时
 * 应转换为显式 Err，而非抛未受检异常。
 */
public class UuidFieldBean {

    private UUID id;

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }
}
