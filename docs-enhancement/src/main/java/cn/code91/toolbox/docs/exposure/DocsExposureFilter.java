package cn.code91.toolbox.docs.exposure;

import cn.code91.toolbox.docs.core.DocsExposureGate;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.env.Environment;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * 文档端点请求期过滤（06 §4.5，裁定 D）：未暴露 → 直接 404，不落入 springdoc handler。
 * 选择请求期过滤而非配置期覆写 springdoc 属性：其它自动装配类的条件求值早于本模块任何
 * bean 处理器能执行的时机，覆写为时已晚（06 §4.5 技术澄清）。urlPatterns 由装配层按
 * springdoc 路径属性计算（读属性不硬编码，设计风险 2）。
 *
 * <p>gate 判定在 doFilter 时求值（非注册期缓存），保证测试与运行语义一致。</p>
 */
public class DocsExposureFilter extends OncePerRequestFilter {

    private final DocsExposureGate gate;
    private final Environment environment;

    public DocsExposureFilter(DocsExposureGate gate, Environment environment) {
        this.gate = gate;
        this.environment = environment;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        if (!gate.isExposed(environment)) {
            response.sendError(HttpServletResponse.SC_NOT_FOUND);
            return;
        }
        filterChain.doFilter(request, response);
    }
}
