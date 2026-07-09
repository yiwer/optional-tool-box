package cn.code91.toolbox.compare.testfixtures;

import java.util.UUID;

/**
 * 含 {@code UUID} 字段的测试夹具。P2 扩表后 {@code UUID} 已是叶子类型
 * （{@code LeafValues#isLeaf}），供叶子矩阵正向用例使用；历史上曾是 I2 回归夹具。
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
