package cn.code91.toolbox.compare.testfixtures;

/**
 * 自引用测试夹具：用于验证环检测。
 */
public class SelfRefBean {

    private String name;

    private SelfRefBean next;

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public SelfRefBean getNext() {
        return next;
    }

    public void setNext(SelfRefBean next) {
        this.next = next;
    }
}
