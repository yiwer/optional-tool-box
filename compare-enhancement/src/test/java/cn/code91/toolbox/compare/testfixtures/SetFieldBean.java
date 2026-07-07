package cn.code91.toolbox.compare.testfixtures;

import java.util.Set;

/**
 * 含 {@code Set} 字段的测试夹具（I2 回归）：P1 集合仅支持 List/数组/Map，
 * 引擎遇到非 List 的 Collection（如 Set）应返回显式 Err，而非抛未受检异常。
 */
public class SetFieldBean {

    private Set<String> tags;

    public Set<String> getTags() {
        return tags;
    }

    public void setTags(Set<String> tags) {
        this.tags = tags;
    }
}
