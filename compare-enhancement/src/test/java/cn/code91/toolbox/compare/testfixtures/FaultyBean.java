package cn.code91.toolbox.compare.testfixtures;

/**
 * getter 抛异常的测试夹具，用于验证字段读取失败时引擎返回 {@code FieldAccessError}。
 */
public class FaultyBean {

    private String risky;

    public String getRisky() {
        throw new IllegalStateException("模拟字段读取失败");
    }

    public void setRisky(String risky) {
        this.risky = risky;
    }
}
