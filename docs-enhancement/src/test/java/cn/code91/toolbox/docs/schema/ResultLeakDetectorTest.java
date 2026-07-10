package cn.code91.toolbox.docs.schema;

import cn.code91.facility.result.Result;
import cn.code91.facility.web.response.BaseResponse;
import cn.code91.facility.web.response.PageBaseResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.ResponseEntity;
import org.springframework.web.context.support.StaticWebApplicationContext;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.lang.reflect.Method;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Result 通道泄漏检测（06 §4.4 契约感知①，裁定 C-1）：启动期告警、不抛异常不阻断启动；
 * 判定 = 声明返回类型 raw class 精确等于 {@code Result}（{@code ResponseEntity<X>} 时取 X 再判）。
 */
@ExtendWith(OutputCaptureExtension.class)
class ResultLeakDetectorTest {

    @Test
    void warnsPerLeakingHandlerMethodWithControllerSimpleNameAndGuidance(CapturedOutput output) {
        RequestMappingHandlerMapping mapping =
                mappingOf(new LeakyController(), "direct", "wrappedInResponseEntity");
        ResultLeakDetector detector = new ResultLeakDetector(providerOf(mapping));

        detector.afterSingletonsInstantiated();

        assertThat(output).contains("LeakyController#direct");
        assertThat(output).contains("LeakyController#wrappedInResponseEntity");
        assertThat(output).contains("BaseResponse.fromResult");
    }

    @Test
    void cleanControllerProducesNoWarning(CapturedOutput output) {
        RequestMappingHandlerMapping mapping =
                mappingOf(new CleanController(), "plain", "wrapped", "page", "voidHandler");
        ResultLeakDetector detector = new ResultLeakDetector(providerOf(mapping));

        detector.afterSingletonsInstantiated();

        assertThat(output).doesNotContain("CleanController");
        assertThat(output).doesNotContain("Result");
    }

    @Test
    void absentHandlerMappingIsANoOp(CapturedOutput output) {
        ResultLeakDetector detector = new ResultLeakDetector(providerOf());

        detector.afterSingletonsInstantiated();

        assertThat(output.getOut()).isEmpty();
    }

    /** 手工注册 HandlerMethod（不经组件扫描），逐方法挂到独立路径上。 */
    private static RequestMappingHandlerMapping mappingOf(Object controller, String... methodNames) {
        StaticWebApplicationContext applicationContext = new StaticWebApplicationContext();
        RequestMappingHandlerMapping mapping = new RequestMappingHandlerMapping();
        mapping.setApplicationContext(applicationContext);
        for (String methodName : methodNames) {
            mapping.registerMapping(
                    RequestMappingInfo.paths("/" + methodName).build(), controller, methodOf(controller, methodName));
        }
        return mapping;
    }

    private static Method methodOf(Object controller, String name) {
        for (Method method : controller.getClass().getDeclaredMethods()) {
            if (method.getName().equals(name)) {
                return method;
            }
        }
        throw new AssertionError("测试控制器缺少方法：" + name);
    }

    private static ObjectProvider<RequestMappingHandlerMapping> providerOf(RequestMappingHandlerMapping... mappings) {
        return new ObjectProvider<>() {
            @Override
            public RequestMappingHandlerMapping getObject() {
                throw new UnsupportedOperationException("测试桩只支持 orderedStream()");
            }

            @Override
            public Stream<RequestMappingHandlerMapping> orderedStream() {
                return Stream.of(mappings);
            }
        };
    }

    static class LeakyController {

        public Result<String, String> direct() {
            return Result.ok("leak");
        }

        public ResponseEntity<Result<String, String>> wrappedInResponseEntity() {
            return ResponseEntity.ok(Result.ok("leak"));
        }
    }

    static class CleanController {

        public BaseResponse<String> plain() {
            return BaseResponse.ok("ok");
        }

        public ResponseEntity<BaseResponse<String>> wrapped() {
            return ResponseEntity.ok(BaseResponse.ok("ok"));
        }

        public PageBaseResponse<String> page() {
            return PageBaseResponse.of(java.util.List.of(), 0, 1, 10);
        }

        public void voidHandler() {
        }
    }
}
