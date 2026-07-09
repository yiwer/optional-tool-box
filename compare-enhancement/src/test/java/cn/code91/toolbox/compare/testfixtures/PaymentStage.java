package cn.code91.toolbox.compare.testfixtures;

/**
 * 带方法体（常量体）的枚举夹具（P2）：AUTHORIZED/CAPTURED 的运行时类是合成匿名子类
 * （{@code PaymentStage$1}/{@code PaymentStage$2}，{@code Class.isEnum()} 为 false），
 * 用于验证叶子判定与类型对可比性按 {@code Enum} 语义而非运行时 Class 处理。
 * {@code toString} 故意覆写为中文描述，验证展示文本取 {@code name()} 而非 {@code toString()}。
 */
public enum PaymentStage {
    AUTHORIZED {
        @Override
        public String toString() {
            return "已授权";
        }
    },
    CAPTURED {
        @Override
        public String toString() {
            return "已扣款";
        }
    }
}
