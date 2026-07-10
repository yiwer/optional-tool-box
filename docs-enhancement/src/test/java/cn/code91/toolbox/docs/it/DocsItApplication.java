package cn.code91.toolbox.docs.it;

import cn.code91.facility.result.Result;
import cn.code91.facility.web.response.BaseResponse;
import cn.code91.facility.web.response.PageBaseResponse;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 集成测试用最小应用（仅测试源码，不随 jar 发布）：{@code @EnableAutoConfiguration} 触发
 * 本模块 imports/spring.factories 与 springdoc 的真实装配链路；三个测试控制器分别覆盖
 * {@code BaseResponse<TestDto>}、{@code PageBaseResponse<TestDto>} 与故意泄漏 {@code Result}
 * 的场景（06 §9 测试策略）。不开组件扫描：嵌套 {@code @RestController} 成员类由配置类
 * 解析自动注册（{@code @Component} 派生），不得再写 {@code @Bean} 方法（会重复注册、
 * Ambiguous mapping 启动失败——首跑实测）。
 */
@SpringBootConfiguration
@EnableAutoConfiguration
public class DocsItApplication {

    /** 演示 DTO：schema 命名修饰的展开目标。 */
    public record TestDto(String name, int amount) {
    }

    @RestController
    static class AdminTestController {

        @GetMapping("/admin/things")
        public BaseResponse<TestDto> listThings() {
            return BaseResponse.ok(new TestDto("thing", 1));
        }
    }

    @RestController
    static class OpenItemsTestController {

        @GetMapping("/api/items")
        public PageBaseResponse<TestDto> listItems() {
            return PageBaseResponse.of(List.of(new TestDto("item", 2)), 1, 1, 10);
        }
    }

    /** 故意的契约破坏者：直接把内部 Result 通道暴露为返回类型（应触发启动期泄漏告警）。 */
    @RestController
    static class LeakyTestController {

        @GetMapping("/api/leak")
        public Result<TestDto, String> leak() {
            return Result.ok(new TestDto("leak", 3));
        }
    }
}
