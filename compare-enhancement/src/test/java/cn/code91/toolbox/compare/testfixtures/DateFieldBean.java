package cn.code91.toolbox.compare.testfixtures;

import java.util.Date;

/**
 * 含 {@code java.util.Date} 字段的测试夹具（I2 回归）：{@code Date} 不在叶子类型表内
 * （见 {@code LeafValues#isLeaf}，只认 {@code TemporalAccessor}），引擎应把它当普通对象递归
 * 展开字段——但 {@code Date} 的私有字段（如 {@code fastTime}/{@code cdate}）触发
 * {@code InaccessibleObjectException} 时应转换为显式 Err，而非抛未受检异常。
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
