package cn.code91.toolbox.storage.core;

import java.time.Instant;

/**
 * 预签名直传/直下 URL。
 *
 * @param url       预签名 URL
 * @param expiresAt 过期时刻
 * @param method    该 URL 应使用的 HTTP 方法（如 {@code PUT}/{@code GET}）
 */
public record PresignedUrl(String url, Instant expiresAt, String method) {
}
