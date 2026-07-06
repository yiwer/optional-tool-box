package cn.code91.toolbox.compare.testfixtures;

import cn.code91.toolbox.compare.annotation.CompareIgnore;
import cn.code91.toolbox.compare.annotation.CompareLabel;

import java.math.BigDecimal;

/**
 * record 测试夹具：验证反射引擎对 record accessor（而非 getter）取值路径的支持。
 */
public record OrderRecord(@CompareIgnore Long id,
                           @CompareLabel("订单金额") BigDecimal amount,
                           @CompareLabel("备注") String remark) {
}
