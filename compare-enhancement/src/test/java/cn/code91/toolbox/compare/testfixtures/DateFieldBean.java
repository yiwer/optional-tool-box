package cn.code91.toolbox.compare.testfixtures;

import java.util.Date;

/**
 * 含 {@code java.util.Date} 字段的测试夹具。P2 扩表后 {@code Date} 已是叶子类型
 * （{@code LeafValues#isLeaf}），供叶子矩阵正向用例使用；历史上曾是 I2 回归夹具
 * （扩表前 Date 走对象图反射并收敛为 Err）。
 */
public class DateFieldBean {

    private Date createdAt;

    public Date getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Date createdAt) {
        this.createdAt = createdAt;
    }
}
