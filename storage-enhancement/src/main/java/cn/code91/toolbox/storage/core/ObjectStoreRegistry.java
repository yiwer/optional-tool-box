package cn.code91.toolbox.storage.core;

/**
 * 逻辑桶名 → {@link ObjectStore} 的注册表。逻辑名与物理资源解耦，切环境只改配置。
 */
public interface ObjectStoreRegistry {

    /**
     * 按逻辑桶名取 {@link ObjectStore}。
     *
     * @throws IllegalArgumentException 逻辑桶名未配置时抛出，消息需包含配置引导
     *                                   （形如 {@code toolbox.storage.<type>.buckets}），
     *                                   帮助排查缺了哪段配置（00-overview.md §3.3）
     */
    ObjectStore get(String logicalBucketName);
}
