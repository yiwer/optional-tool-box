package cn.code91.toolbox.compare.testfixtures;

/**
 * 链式嵌套测试夹具：用于验证深度超限检测（无环，纯粹链条比 maxDepth 更深）。
 */
public class ChainBean {

    private String value;

    private ChainBean child;

    public String getValue() {
        return value;
    }

    public void setValue(String value) {
        this.value = value;
    }

    public ChainBean getChild() {
        return child;
    }

    public void setChild(ChainBean child) {
        this.child = child;
    }
}
