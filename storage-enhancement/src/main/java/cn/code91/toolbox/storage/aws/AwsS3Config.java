package cn.code91.toolbox.storage.aws;

/**
 * {@link AwsS3ClientFactory} 的连接参数。aws 包不得依赖 autoconfigure（ArchUnit 约束），
 * 故不直接使用 {@code ToolboxStorageProperties}，由装配层转换后传入。
 *
 * @param endpoint          自定义 endpoint（MinIO/COS/OBS 兼容场景）；标准 AWS S3 场景可为空，
 *                          由 region 决定默认 endpoint
 * @param region            AWS region（MinIO 场景可传任意占位值，如 {@code "minio"}）
 * @param pathStyleAccess   是否启用 path-style 访问（MinIO 等自建 S3 兼容服务通常需要 true）
 * @param accessKeyId       可选静态凭证 AK；与 {@code accessKeySecret} 成对给出才生效
 * @param accessKeySecret   可选静态凭证 SK；与 {@code accessKeyId} 成对给出才生效
 */
public record AwsS3Config(
        String endpoint,
        String region,
        boolean pathStyleAccess,
        String accessKeyId,
        String accessKeySecret) {
}
