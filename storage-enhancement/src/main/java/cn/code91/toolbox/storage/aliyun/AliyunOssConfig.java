package cn.code91.toolbox.storage.aliyun;

/**
 * {@link AliyunOssClientFactory} 的连接参数。aliyun 包不得依赖 autoconfigure（ArchUnit 约束），
 * 故不直接使用 {@code ToolboxStorageProperties}，由装配层转换后传入。
 *
 * @param endpoint        OSS endpoint（如 {@code https://oss-cn-hangzhou.aliyuncs.com}）
 * @param ecsRamRole       ECS RAM 角色名；优先于 AK/SK（凭证裁定 B）
 * @param accessKeyId      可选静态凭证 AK（ecs-ram-role 未配置时使用）
 * @param accessKeySecret  可选静态凭证 SK（ecs-ram-role 未配置时使用）
 */
public record AliyunOssConfig(
        String endpoint,
        String ecsRamRole,
        String accessKeyId,
        String accessKeySecret) {
}
