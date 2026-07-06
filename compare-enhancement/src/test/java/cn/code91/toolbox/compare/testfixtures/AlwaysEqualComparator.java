package cn.code91.toolbox.compare.testfixtures;

import cn.code91.toolbox.compare.spi.ValueComparator;

/**
 * 测试用比较器：始终判定相等，用于验证 {@code @CompareWith} 优先级覆盖内置/兜底逻辑。
 */
public class AlwaysEqualComparator implements ValueComparator<String> {

    @Override
    public Class<String> type() {
        return String.class;
    }

    @Override
    public boolean isEqual(String a, String b) {
        return true;
    }
}
