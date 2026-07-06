package cn.code91.toolbox.storage.aws;

import cn.code91.facility.log.LogUtil;
import cn.code91.facility.result.Result;
import cn.code91.toolbox.storage.core.ObjectKey;
import cn.code91.toolbox.storage.core.ObjectMetadata;
import cn.code91.toolbox.storage.core.ObjectStore;
import cn.code91.toolbox.storage.core.PresignedUrl;
import cn.code91.toolbox.storage.core.StorageError;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;
import software.amazon.awssdk.services.s3.model.S3Object;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PresignedGetObjectRequest;
import software.amazon.awssdk.services.s3.presigner.model.PresignedPutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

import java.io.InputStream;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * AWS S3（及一切 S3 兼容：MinIO/腾讯 COS/华为 OBS）{@link ObjectStore} 实现。
 * 每个方法遵循统一形态：调用 SDK → 映射成功结果 → 经 {@link AwsS3ErrorMapper} 收敛异常，
 * 保证公开 API 零异常外抛。
 */
public final class AwsS3ObjectStore implements ObjectStore {

    private final String bucketName;
    private final String physicalBucket;
    private final S3Client client;
    private final S3Presigner presigner;
    private final AwsS3ErrorMapper errorMapper;

    public AwsS3ObjectStore(String bucketName, String physicalBucket, S3Client client, S3Presigner presigner) {
        this.bucketName = bucketName;
        this.physicalBucket = physicalBucket;
        this.client = client;
        this.presigner = presigner;
        this.errorMapper = new AwsS3ErrorMapper();
    }

    @Override
    public String bucketName() {
        return bucketName;
    }

    @Override
    public Result<ObjectMetadata, StorageError> put(String key, InputStream data, long contentLength, String contentType) {
        try {
            PutObjectRequest request = PutObjectRequest.builder()
                    .bucket(physicalBucket)
                    .key(key)
                    .contentType(contentType)
                    .contentLength(contentLength)
                    .build();
            PutObjectResponse response = client.putObject(request, RequestBody.fromInputStream(data, contentLength));
            LogUtil.debug("aws-s3 写入对象：bucket={}, key={}, size={}", physicalBucket, key, contentLength);
            return Result.ok(new ObjectMetadata(key, contentLength, contentType, nullToEmpty(response.eTag()), Instant.now()));
        } catch (Exception e) {
            return Result.err(errorMapper.map(e, key));
        }
    }

    @Override
    public Result<InputStream, StorageError> get(String key) {
        try {
            GetObjectRequest request = GetObjectRequest.builder().bucket(physicalBucket).key(key).build();
            return Result.ok(client.getObject(request));
        } catch (Exception e) {
            return Result.err(errorMapper.map(e, key));
        }
    }

    @Override
    public Result<List<ObjectKey>, StorageError> list(String prefix, int maxKeys) {
        try {
            ListObjectsV2Request request = ListObjectsV2Request.builder()
                    .bucket(physicalBucket)
                    .prefix(prefix)
                    .maxKeys(maxKeys)
                    .build();
            ListObjectsV2Response response = client.listObjectsV2(request);
            List<ObjectKey> keys = response.contents().stream()
                    .map(AwsS3ObjectStore::toObjectKey)
                    .toList();
            return Result.ok(keys);
        } catch (Exception e) {
            return Result.err(errorMapper.map(e, prefix));
        }
    }

    private static ObjectKey toObjectKey(S3Object object) {
        return new ObjectKey(object.key(), object.size() == null ? 0L : object.size(), object.lastModified());
    }

    @Override
    public Result<Void, StorageError> delete(String key) {
        try {
            DeleteObjectRequest request = DeleteObjectRequest.builder().bucket(physicalBucket).key(key).build();
            client.deleteObject(request);
            return Result.ok();
        } catch (Exception e) {
            return Result.err(errorMapper.map(e, key));
        }
    }

    @Override
    public Result<Boolean, StorageError> exists(String key) {
        try {
            HeadObjectRequest request = HeadObjectRequest.builder().bucket(physicalBucket).key(key).build();
            client.headObject(request);
            return Result.ok(true);
        } catch (NoSuchKeyException e) {
            return Result.ok(false);
        } catch (Exception e) {
            return Result.err(errorMapper.map(e, key));
        }
    }

    @Override
    public Result<PresignedUrl, StorageError> presignedPut(String key, Duration expires, String contentType) {
        try {
            PutObjectRequest putRequest = PutObjectRequest.builder()
                    .bucket(physicalBucket)
                    .key(key)
                    .contentType(contentType)
                    .build();
            PutObjectPresignRequest presignRequest = PutObjectPresignRequest.builder()
                    .signatureDuration(expires)
                    .putObjectRequest(putRequest)
                    .build();
            PresignedPutObjectRequest presigned = presigner.presignPutObject(presignRequest);
            return Result.ok(new PresignedUrl(presigned.url().toString(), presigned.expiration(), "PUT"));
        } catch (Exception e) {
            return Result.err(errorMapper.map(e, key));
        }
    }

    @Override
    public Result<PresignedUrl, StorageError> presignedGet(String key, Duration expires) {
        try {
            GetObjectRequest getRequest = GetObjectRequest.builder().bucket(physicalBucket).key(key).build();
            GetObjectPresignRequest presignRequest = GetObjectPresignRequest.builder()
                    .signatureDuration(expires)
                    .getObjectRequest(getRequest)
                    .build();
            PresignedGetObjectRequest presigned = presigner.presignGetObject(presignRequest);
            return Result.ok(new PresignedUrl(presigned.url().toString(), presigned.expiration(), "GET"));
        } catch (Exception e) {
            return Result.err(errorMapper.map(e, key));
        }
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
