package cn.code91.toolbox.storage.aliyun;

import cn.code91.facility.log.LogUtil;
import cn.code91.facility.result.Result;
import cn.code91.toolbox.storage.core.ObjectKey;
import cn.code91.toolbox.storage.core.ObjectMetadata;
import cn.code91.toolbox.storage.core.ObjectStore;
import cn.code91.toolbox.storage.core.PresignedUrl;
import cn.code91.toolbox.storage.core.StorageError;
import com.aliyun.oss.HttpMethod;
import com.aliyun.oss.OSS;
import com.aliyun.oss.model.GeneratePresignedUrlRequest;
import com.aliyun.oss.model.ListObjectsV2Request;
import com.aliyun.oss.model.ListObjectsV2Result;
import com.aliyun.oss.model.OSSObject;
import com.aliyun.oss.model.OSSObjectSummary;
import com.aliyun.oss.model.PutObjectResult;

import java.io.InputStream;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;

/**
 * 阿里云 OSS {@link ObjectStore} 实现。每个方法遵循统一形态：调用 SDK → 映射成功结果 →
 * 经 {@link AliyunOssErrorMapper} 收敛异常，保证公开 API 零异常外抛。
 */
public final class AliyunOssObjectStore implements ObjectStore {

    private final String bucketName;
    private final String physicalBucket;
    private final OSS client;
    private final AliyunOssErrorMapper errorMapper;

    public AliyunOssObjectStore(String bucketName, String physicalBucket, OSS client) {
        this.bucketName = bucketName;
        this.physicalBucket = physicalBucket;
        this.client = client;
        this.errorMapper = new AliyunOssErrorMapper();
    }

    @Override
    public String bucketName() {
        return bucketName;
    }

    @Override
    public Result<ObjectMetadata, StorageError> put(String key, InputStream data, long contentLength, String contentType) {
        try {
            com.aliyun.oss.model.ObjectMetadata metadata = new com.aliyun.oss.model.ObjectMetadata();
            metadata.setContentLength(contentLength);
            metadata.setContentType(contentType);
            PutObjectResult result = client.putObject(physicalBucket, key, data, metadata);
            LogUtil.debug("aliyun-oss 写入对象：bucket={}, key={}, size={}", physicalBucket, key, contentLength);
            return Result.ok(new ObjectMetadata(key, contentLength, contentType, nullToEmpty(result.getETag()), Instant.now()));
        } catch (Exception e) {
            return Result.err(errorMapper.map(e, key));
        }
    }

    @Override
    public Result<InputStream, StorageError> get(String key) {
        try {
            OSSObject object = client.getObject(physicalBucket, key);
            return Result.ok(object.getObjectContent());
        } catch (Exception e) {
            return Result.err(errorMapper.map(e, key));
        }
    }

    @Override
    public Result<List<ObjectKey>, StorageError> list(String prefix, int maxKeys) {
        try {
            ListObjectsV2Request request = new ListObjectsV2Request(physicalBucket);
            request.setPrefix(prefix);
            request.setMaxKeys(maxKeys);
            ListObjectsV2Result result = client.listObjectsV2(request);
            List<ObjectKey> keys = result.getObjectSummaries().stream()
                    .map(AliyunOssObjectStore::toObjectKey)
                    .toList();
            return Result.ok(keys);
        } catch (Exception e) {
            return Result.err(errorMapper.map(e, prefix));
        }
    }

    private static ObjectKey toObjectKey(OSSObjectSummary summary) {
        return new ObjectKey(summary.getKey(), summary.getSize(), toInstant(summary.getLastModified()));
    }

    private static Instant toInstant(Date date) {
        return date == null ? Instant.now() : date.toInstant();
    }

    @Override
    public Result<Void, StorageError> delete(String key) {
        try {
            client.deleteObject(physicalBucket, key);
            return Result.ok();
        } catch (Exception e) {
            return Result.err(errorMapper.map(e, key));
        }
    }

    @Override
    public Result<Boolean, StorageError> exists(String key) {
        try {
            return Result.ok(client.doesObjectExist(physicalBucket, key));
        } catch (Exception e) {
            return Result.err(errorMapper.map(e, key));
        }
    }

    @Override
    public Result<PresignedUrl, StorageError> presignedPut(String key, Duration expires, String contentType) {
        try {
            GeneratePresignedUrlRequest request = new GeneratePresignedUrlRequest(physicalBucket, key, HttpMethod.PUT);
            request.setExpiration(Date.from(Instant.now().plus(expires)));
            request.setContentType(contentType);
            java.net.URL url = client.generatePresignedUrl(request);
            return Result.ok(new PresignedUrl(url.toString(), Instant.now().plus(expires), "PUT"));
        } catch (Exception e) {
            return Result.err(errorMapper.map(e, key));
        }
    }

    @Override
    public Result<PresignedUrl, StorageError> presignedGet(String key, Duration expires) {
        try {
            Date expiration = Date.from(Instant.now().plus(expires));
            java.net.URL url = client.generatePresignedUrl(physicalBucket, key, expiration, HttpMethod.GET);
            return Result.ok(new PresignedUrl(url.toString(), expiration.toInstant(), "GET"));
        } catch (Exception e) {
            return Result.err(errorMapper.map(e, key));
        }
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
