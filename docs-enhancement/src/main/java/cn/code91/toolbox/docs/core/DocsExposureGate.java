package cn.code91.toolbox.docs.core;

import org.springframework.core.env.Environment;

/**
 * 文档暴露门禁 Seam（06 §4.1/§4.5，L4）：判定是否应暴露 api-docs / swagger-ui / 导出端点。
 * 只做"暴露开关"，不做访问鉴权（谁能看归 P2 {@code DocsAccessGuard}，06 §2.3 #3）；
 * 判定在请求期（doFilter 时）求值，非注册期缓存。
 */
public interface DocsExposureGate {

    boolean isExposed(Environment env);
}
