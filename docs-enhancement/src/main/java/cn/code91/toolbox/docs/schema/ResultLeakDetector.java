package cn.code91.toolbox.docs.schema;

import cn.code91.facility.log.LogUtil;
import cn.code91.facility.result.Result;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.core.ResolvableType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * Result 通道泄漏检测（06 §4.4 契约感知①，裁定 C-1）：容器刷新末尾遍历所有
 * {@link RequestMappingHandlerMapping} 的 HandlerMethod，对声明返回类型 raw class 精确等于
 * {@link Result}（{@code ResponseEntity<X>} 时取 X 再判）的方法逐一告警——正确做法是先经
 * {@code BaseResponse.fromResult(...)} 转换。<b>只告警、不抛异常、不阻断启动</b>：本模块不该
 * 成为业务应用"能不能启动"的强控制点（strict 模式属 P2，裁定 A 排除）。
 */
public class ResultLeakDetector implements SmartInitializingSingleton {

    private final ObjectProvider<RequestMappingHandlerMapping> handlerMappings;

    public ResultLeakDetector(ObjectProvider<RequestMappingHandlerMapping> handlerMappings) {
        this.handlerMappings = handlerMappings;
    }

    @Override
    public void afterSingletonsInstantiated() {
        handlerMappings.orderedStream()
                .forEach(mapping -> mapping.getHandlerMethods().values().forEach(ResultLeakDetector::inspect));
    }

    private static void inspect(HandlerMethod handlerMethod) {
        ResolvableType returnType = ResolvableType.forMethodReturnType(handlerMethod.getMethod());
        if (returnType.resolve() == ResponseEntity.class) {
            returnType = returnType.getGeneric(0);
        }
        if (returnType.resolve() == Result.class) {
            LogUtil.warn("检测到控制器方法把内部 Result 通道直接声明为返回类型：{}#{}，"
                            + "应经 BaseResponse.fromResult(...) 转换后再暴露"
                            + "（springdoc 会对 sealed 接口生成混乱或失败的 schema）",
                    handlerMethod.getBeanType().getSimpleName(), handlerMethod.getMethod().getName());
        }
    }
}
