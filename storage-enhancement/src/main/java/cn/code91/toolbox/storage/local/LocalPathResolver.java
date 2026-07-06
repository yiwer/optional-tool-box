package cn.code91.toolbox.storage.local;

import cn.code91.facility.path.Filenames;

import java.nio.file.Path;
import java.util.Optional;

/**
 * key → 桶子目录下的物理路径解析，含路径穿越防御。
 *
 * <p>裁定与 facility {@code Filenames.sanitize()} 的关系（如实披露）：{@code sanitize()}
 * 面向"单一文件名"语义——会把任何路径前缀剥离到只剩 basename，若直接套用在对象存储 key 上
 * （key 惯例含 {@code /} 伪目录分隔符，如 {@code photos/2026/img.jpg}）会摧毁合法的层级结构。
 * 故本类改用 {@code resolve().normalize().startsWith(root)} 的经典穿越防御模式
 * （与 facility {@code SafeUpload.saveFile} 同源手法的推广：该方法在单段文件名场景下
 * 也做了同样的 resolve+normalize+startsWith 二次校验，见其源码 52-56 行），只在提取扩展名时
 * 复用 {@link Filenames#isDangerousExtension}/{@link Filenames#extension}
 * （这两个方法按"最后一个点号切分"工作，对多段 key 同样正确）。</p>
 */
final class LocalPathResolver {

    private final Path bucketRoot;

    LocalPathResolver(Path bucketRoot) {
        this.bucketRoot = bucketRoot.normalize().toAbsolutePath();
    }

    /**
     * 解析 key 到桶目录下的物理路径；key 为空白、解析后逃逸出桶目录（{@code ..} 穿越、
     * 绝对路径改写等）时返回空。
     */
    Optional<Path> resolve(String key) {
        if (key == null || key.isBlank()) {
            return Optional.empty();
        }
        Path candidate = bucketRoot.resolve(key).normalize();
        if (!candidate.startsWith(bucketRoot) || candidate.equals(bucketRoot)) {
            return Optional.empty();
        }
        return Optional.of(candidate);
    }

    Path bucketRoot() {
        return bucketRoot;
    }
}
