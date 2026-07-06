package cn.code91.toolbox.storage.core;

import java.util.Map;

/**
 * {@link ObjectStoreRegistry} 默认实现：不可变 map 查找，未知逻辑桶名抛
 * {@link IllegalArgumentException}，消息含 {@code toolbox.storage.<type>.buckets} 配置引导
 * （00-overview.md §3.3：未配置时装配"空 Registry"兜底，模块不报错、应用能启动；
 * {@code get()} 时才抛带引导信息的异常）。
 */
public final class DefaultObjectStoreRegistry implements ObjectStoreRegistry {

    private final Map<String, ObjectStore> stores;
    private final String type;

    /**
     * @param stores 逻辑桶名 → {@link ObjectStore}；未配置 type 时传空 map
     * @param type   当前生效的 {@code toolbox.storage.type}（{@code aws-s3}/{@code aliyun-oss}/
     *               {@code local}），未配置时为 {@code null}——异常消息据此调整引导语，
     *               避免在"连 type 都没配"时误导用户去看某个具体 provider 的 buckets 配置
     */
    public DefaultObjectStoreRegistry(Map<String, ObjectStore> stores, String type) {
        this.stores = Map.copyOf(stores);
        this.type = type;
    }

    @Override
    public ObjectStore get(String logicalBucketName) {
        ObjectStore store = stores.get(logicalBucketName);
        if (store == null) {
            throw new IllegalArgumentException(buildGuidanceMessage(logicalBucketName));
        }
        return store;
    }

    private String buildGuidanceMessage(String logicalBucketName) {
        if (type == null || type.isBlank()) {
            return "未找到逻辑桶 \"" + logicalBucketName + "\"：当前未配置 toolbox.storage.type，"
                    + "请设置 toolbox.storage.type（aws-s3 | aliyun-oss | local）并在对应 "
                    + "toolbox.storage.<type>.buckets." + logicalBucketName + " 下声明物理桶";
        }
        return "未找到逻辑桶 \"" + logicalBucketName + "\"：请在 toolbox.storage." + type
                + ".buckets." + logicalBucketName + " 下声明物理桶（当前已配置 buckets："
                + stores.keySet() + "）";
    }
}
