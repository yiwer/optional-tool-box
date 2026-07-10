package cn.code91.toolbox.auth.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.io.IOException;

/**
 * 方法级安全异常的 MVC 层出口（Task 6 全链矩阵实证补件，07 §4.3"形态处处一致"承诺的组成部分）。
 * {@code @PreAuthorize} 拒绝抛出的 {@link AccessDeniedException} 发生在 controller 调用内，
 * 走 MVC 异常解析而非链上 ExceptionTranslationFilter——若无本 advice，facility 的全局兜底
 * {@code @ExceptionHandler(Exception.class)}（默认 LOWEST_PRECEDENCE）会把它吃成 200/500。
 * 本 advice 以高优先级抢先按 07 §4.3 形态写出 401/403，其余异常不碰（仍归 facility）。
 * 注：web 包"实现类不引 spring-web"约束（07 §4.3）针对 EntryPoint/Filter；本类是 MVC 组件，
 * 必然依赖 spring-web 注解（optional 编译面依赖已由 Task 5 裁定引入）。
 */
@RestControllerAdvice
@Order(SecurityExceptionAdvice.ORDER)
public class SecurityExceptionAdvice {

    /** 高优先级但可被消费方超越（Ordered.HIGHEST_PRECEDENCE + 1000，L4 精神） */
    public static final int ORDER = Ordered.HIGHEST_PRECEDENCE + 1000;

    private final AuthEntryPoint entryPoint = new AuthEntryPoint();
    private final AuthAccessDeniedHandler deniedHandler = new AuthAccessDeniedHandler();

    /** 方法级授权拒绝 → 403（与链上 AuthorizationFilter 路径同形态） */
    @ExceptionHandler(AccessDeniedException.class)
    public void handleAccessDenied(HttpServletRequest request, HttpServletResponse response,
                                   AccessDeniedException ex) throws IOException {
        deniedHandler.handle(request, response, ex);
    }

    /** MVC 层认证异常（罕见：permit 路径上匿名触达受保护方法等） → 401 */
    @ExceptionHandler(AuthenticationException.class)
    public void handleAuthentication(HttpServletRequest request, HttpServletResponse response,
                                     AuthenticationException ex) throws IOException {
        entryPoint.commence(request, response, ex);
    }
}
