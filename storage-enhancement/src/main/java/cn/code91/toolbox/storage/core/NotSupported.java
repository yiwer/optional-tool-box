package cn.code91.toolbox.storage.core;

/**
 * provider 不具备该能力（如 local adapter 的预签名），坦诚化而非硬模拟/静默降级
 * （00-overview.md §2 原则 7）。
 *
 * @param message 说明缺失的能力与原因
 */
public record NotSupported(String message) implements StorageError {

    public static NotSupported of(String message) {
        return new NotSupported(message);
    }
}
