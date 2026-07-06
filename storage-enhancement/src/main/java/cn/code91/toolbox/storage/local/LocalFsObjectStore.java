package cn.code91.toolbox.storage.local;

import cn.code91.facility.log.LogUtil;
import cn.code91.facility.path.Filenames;
import cn.code91.facility.result.Result;
import cn.code91.toolbox.storage.core.NetworkError;
import cn.code91.toolbox.storage.core.NotFound;
import cn.code91.toolbox.storage.core.NotSupported;
import cn.code91.toolbox.storage.core.ObjectKey;
import cn.code91.toolbox.storage.core.ObjectMetadata;
import cn.code91.toolbox.storage.core.ObjectStore;
import cn.code91.toolbox.storage.core.PresignedUrl;
import cn.code91.toolbox.storage.core.StorageError;
import cn.code91.toolbox.storage.core.ValidationError;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * 本地文件系统 {@link ObjectStore}：root-dir 下按逻辑桶建子目录，定位为<b>开发/测试便利</b>，
 * 非生产建议（USAGE 声明）。
 *
 * <p>key 经 {@link LocalPathResolver} 校验（穿越防御，见该类 Javadoc 对与
 * {@code Filenames.sanitize()} 关系的说明）；读写用 JDK {@link Files} 直接操作
 * （facility {@code PathIo} 仅提供 {@code deleteDirectory}/{@code directorySize} 两个
 * 递归目录操作，未覆盖单对象 get/put/list/exists 这类场景，其 Javadoc 本身也建议这类操作
 * 直接用 JDK API）；presigned 见控制器裁定 C：固定返回 {@code NotSupported}。</p>
 */
public final class LocalFsObjectStore implements ObjectStore {

    private final String bucketName;
    private final LocalPathResolver resolver;

    public LocalFsObjectStore(String bucketName, Path rootDir) {
        this.bucketName = bucketName;
        Path bucketRoot = rootDir.resolve(bucketName);
        try {
            Files.createDirectories(bucketRoot);
        } catch (IOException e) {
            throw new UncheckedIOException("无法创建本地存储桶目录：" + bucketRoot, e);
        }
        this.resolver = new LocalPathResolver(bucketRoot);
    }

    @Override
    public String bucketName() {
        return bucketName;
    }

    @Override
    public Result<ObjectMetadata, StorageError> put(String key, InputStream data, long contentLength, String contentType) {
        if (Filenames.isDangerousExtension(key)) {
            return Result.err(ValidationError.of("key \"" + key + "\" 使用危险扩展名（内置黑名单），拒绝写入"));
        }
        return resolvePath(key).flatMap(path -> doPut(key, path, data, contentLength, contentType));
    }

    private Result<ObjectMetadata, StorageError> doPut(
            String key, Path path, InputStream data, long contentLength, String contentType) {
        try {
            Files.createDirectories(path.getParent());
            Files.copy(data, path, StandardCopyOption.REPLACE_EXISTING);
            Instant lastModified = Files.getLastModifiedTime(path).toInstant();
            LogUtil.debug("local adapter 写入对象：bucket={}, key={}, size={}", bucketName, key, contentLength);
            return Result.ok(new ObjectMetadata(key, contentLength, contentType, "", lastModified));
        } catch (IOException e) {
            return Result.err(NetworkError.of("写入本地文件失败：" + path, e));
        }
    }

    @Override
    public Result<InputStream, StorageError> get(String key) {
        return resolvePath(key).flatMap(path -> {
            if (!Files.isRegularFile(path)) {
                return Result.err(NotFound.of(key));
            }
            try {
                return Result.ok(Files.newInputStream(path));
            } catch (IOException e) {
                return Result.err(NetworkError.of("读取本地文件失败：" + path, e));
            }
        });
    }

    @Override
    public Result<List<ObjectKey>, StorageError> list(String prefix, int maxKeys) {
        String normalizedPrefix = prefix == null ? "" : prefix;
        Path bucketRoot = resolver.bucketRoot();
        try (Stream<Path> walk = Files.walk(bucketRoot)) {
            List<ObjectKey> result = new ArrayList<>();
            for (Path path : (Iterable<Path>) walk.filter(Files::isRegularFile)::iterator) {
                String key = toKey(bucketRoot, path);
                if (key.startsWith(normalizedPrefix)) {
                    result.add(new ObjectKey(key, Files.size(path), Files.getLastModifiedTime(path).toInstant()));
                }
                if (result.size() >= maxKeys) {
                    break;
                }
            }
            return Result.ok(result);
        } catch (IOException e) {
            return Result.err(NetworkError.of("列举本地目录失败：" + bucketRoot, e));
        }
    }

    private static String toKey(Path bucketRoot, Path file) {
        return bucketRoot.relativize(file).toString().replace('\\', '/');
    }

    @Override
    public Result<Void, StorageError> delete(String key) {
        return resolvePath(key).flatMap(path -> {
            try {
                Files.deleteIfExists(path);
                return Result.ok();
            } catch (IOException e) {
                return Result.err(NetworkError.of("删除本地文件失败：" + path, e));
            }
        });
    }

    @Override
    public Result<Boolean, StorageError> exists(String key) {
        return resolvePath(key).map(Files::isRegularFile);
    }

    @Override
    public Result<PresignedUrl, StorageError> presignedPut(String key, Duration expires, String contentType) {
        return Result.err(NotSupported.of(
                "local adapter 不支持预签名 URL（本地文件系统无签名语义），请使用应用自身的下载/上传接口"));
    }

    @Override
    public Result<PresignedUrl, StorageError> presignedGet(String key, Duration expires) {
        return Result.err(NotSupported.of(
                "local adapter 不支持预签名 URL（本地文件系统无签名语义），请使用应用自身的下载/上传接口"));
    }

    /**
     * key 解析为物理路径；解析失败（穿越攻击、空白 key 等）统一映射为 {@link ValidationError}。
     */
    private Result<Path, StorageError> resolvePath(String key) {
        Optional<Path> resolved = resolver.resolve(key);
        if (resolved.isEmpty()) {
            return Result.err(ValidationError.of("key \"" + key + "\" 非法（可能包含路径穿越或为空），拒绝访问"));
        }
        return Result.ok(resolved.get());
    }
}
