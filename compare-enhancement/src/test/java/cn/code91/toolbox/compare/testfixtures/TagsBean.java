package cn.code91.toolbox.compare.testfixtures;

import cn.code91.toolbox.compare.annotation.CompareLabel;

import java.util.Map;

/**
 * Map 字段测试夹具：验证 Map 按 key 配对的对比规则。
 */
public class TagsBean {

    @CompareLabel("标签")
    private Map<String, String> tags;

    public Map<String, String> getTags() {
        return tags;
    }

    public void setTags(Map<String, String> tags) {
        this.tags = tags;
    }
}
