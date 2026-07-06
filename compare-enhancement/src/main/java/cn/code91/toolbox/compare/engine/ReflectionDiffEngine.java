package cn.code91.toolbox.compare.engine;

import cn.code91.facility.result.Result;
import cn.code91.toolbox.compare.core.ChangeKind;
import cn.code91.toolbox.compare.core.CompareError;
import cn.code91.toolbox.compare.core.CycleDetected;
import cn.code91.toolbox.compare.core.DepthExceeded;
import cn.code91.toolbox.compare.core.DiffEngine;
import cn.code91.toolbox.compare.core.DiffOptions;
import cn.code91.toolbox.compare.core.DiffResult;
import cn.code91.toolbox.compare.core.FieldAccessError;
import cn.code91.toolbox.compare.core.FieldChange;
import cn.code91.toolbox.compare.core.TypeMismatch;
import cn.code91.toolbox.compare.spi.CompareHandlerRegistry;
import cn.code91.toolbox.compare.spi.ValueComparator;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 反射驱动的差异引擎实现。遍历规则见 01 设计文档 §4.3，集合范围按控制器裁定 B 收窄为
 * BY_INDEX（List/数组）与 key 配对（Map），不实现 BY_KEY/AS_SET/{@code @CompareKey}。
 */
public final class ReflectionDiffEngine implements DiffEngine {

    private static final String DEFAULT_DATE_PATTERN = "yyyy-MM-dd HH:mm:ss";

    private final CompareHandlerRegistry registry;
    private final ClassMetaCache metaCache = new ClassMetaCache();
    private final String datePattern;

    public ReflectionDiffEngine(CompareHandlerRegistry registry) {
        this(registry, DEFAULT_DATE_PATTERN);
    }

    public ReflectionDiffEngine(CompareHandlerRegistry registry, String datePattern) {
        this.registry = Objects.requireNonNull(registry, "registry cannot be null");
        this.datePattern = Objects.requireNonNull(datePattern, "datePattern cannot be null");
    }

    @Override
    public <T> Result<DiffResult, CompareError> diff(T oldValue, T newValue) {
        return diff(oldValue, newValue, DiffOptions.defaults());
    }

    @Override
    public <T> Result<DiffResult, CompareError> diff(T oldValue, T newValue, DiffOptions options) {
        Objects.requireNonNull(options, "options cannot be null");
        Traversal traversal = new Traversal(options);
        try {
            List<FieldChange> changes = new ArrayList<>();
            traversal.compare("", null, oldValue, newValue, 0, changes);
            return Result.ok(new DiffResult(changes));
        } catch (CompareAbortException e) {
            return Result.err(e.error());
        }
    }

    /**
     * 单次 diff 调用内的遍历状态：递归栈上的对象环检测、深度计数。非线程安全，每次
     * {@link #diff(Object, Object, DiffOptions)} 调用各自持有一个实例。
     */
    private final class Traversal {

        private final DiffOptions options;
        private final IdentityHashMap<Object, Boolean> recursionStack = new IdentityHashMap<>();

        Traversal(DiffOptions options) {
            this.options = options;
        }

        /**
         * 比较 path 位置的一对新旧值，追加变更到 changes。
         *
         * @param path     当前字段路径（顶层为空串）
         * @param label    展示标签（顶层为 null，不产出顶层自身的 FieldChange）
         * @param oldValue 旧值
         * @param newValue 新值
         * @param depth    当前递归深度（顶层对象为 0）
         */
        void compare(String path, String label, Object oldValue, Object newValue, int depth, List<FieldChange> changes) {
            if (oldValue == null && newValue == null) {
                return;
            }
            if (oldValue == null || newValue == null) {
                recordAddedOrRemoved(path, label, oldValue, newValue, changes);
                return;
            }

            Class<?> oldType = oldValue.getClass();
            Class<?> newType = newValue.getClass();
            if (!isComparableTypePair(oldType, newType)) {
                throw new CompareAbortException(TypeMismatch.of(path, oldType, newType));
            }

            ValueComparator<Object> customComparator = registry.findComparator((Class<Object>) oldType);
            if (LeafValues.isLeaf(oldType) || customComparator != null) {
                compareLeaf(path, label, oldValue, newValue, customComparator, changes);
                return;
            }
            if (oldValue instanceof Map<?, ?> || newValue instanceof Map<?, ?>) {
                compareMap(path, (Map<Object, Object>) oldValue, (Map<Object, Object>) newValue, depth, changes);
                return;
            }
            if (oldType.isArray() || oldValue instanceof List<?>) {
                compareIndexedCollection(path, oldValue, newValue, depth, changes);
                return;
            }
            compareObjectGraph(path, oldValue, newValue, depth, changes);
        }

        /**
         * 字段级 {@code @CompareWith} 优先，其次应用/内置类型级比较器，最后 {@code Objects.equals} 兜底。
         */
        private void compareLeaf(String path, String label, Object oldValue, Object newValue,
                                  ValueComparator<Object> typeLevelComparator, List<FieldChange> changes) {
            boolean equal = typeLevelComparator != null
                    ? typeLevelComparator.isEqual(oldValue, newValue)
                    : LeafValues.equalsLeaf(oldValue, newValue);
            if (equal) {
                return;
            }
            changes.add(new FieldChange(path, label, ChangeKind.MODIFIED, oldValue, newValue,
                    LeafValues.toText(oldValue, datePattern), LeafValues.toText(newValue, datePattern)));
        }

        /**
         * 字段级 {@code @CompareWith} 比较器实例覆盖叶子/类型级判断，供 {@link #compareField} 调用。
         */
        private void compareLeafWithFieldOverride(String path, String label, Object oldValue, Object newValue,
                                                   ValueComparator<Object> fieldComparator, List<FieldChange> changes) {
            if (fieldComparator.isEqual(oldValue, newValue)) {
                return;
            }
            changes.add(new FieldChange(path, label, ChangeKind.MODIFIED, oldValue, newValue,
                    LeafValues.toText(oldValue, datePattern), LeafValues.toText(newValue, datePattern)));
        }

        private void compareObjectGraph(String path, Object oldValue, Object newValue, int depth, List<FieldChange> changes) {
            if (depth >= options.maxDepth()) {
                throw new CompareAbortException(DepthExceeded.of(path, options.maxDepth()));
            }
            if (recursionStack.containsKey(oldValue) || recursionStack.containsKey(newValue)) {
                throw new CompareAbortException(CycleDetected.of(path));
            }
            recursionStack.put(oldValue, Boolean.TRUE);
            recursionStack.put(newValue, Boolean.TRUE);
            try {
                for (FieldMeta field : metaCache.fieldsOf(oldValue.getClass())) {
                    compareField(path, field, oldValue, newValue, depth, changes);
                }
            } finally {
                recursionStack.remove(oldValue);
                recursionStack.remove(newValue);
            }
        }

        private void compareField(String parentPath, FieldMeta field, Object oldParent, Object newParent,
                                   int depth, List<FieldChange> changes) {
            String path = parentPath.isEmpty() ? field.name() : parentPath + "." + field.name();
            if (!options.isPathIncluded(path)) {
                return;
            }
            Object oldFieldValue = readField(field, oldParent, path);
            Object newFieldValue = readField(field, newParent, path);

            if (field.compareWith() != null) {
                Object normalizedOld = normalizeNullAsEmpty(oldFieldValue);
                Object normalizedNew = normalizeNullAsEmpty(newFieldValue);
                if (normalizedOld == null && normalizedNew == null) {
                    return;
                }
                if (normalizedOld == null || normalizedNew == null) {
                    recordAddedOrRemoved(path, field.label(), normalizedOld, normalizedNew, changes);
                    return;
                }
                compareLeafWithFieldOverride(path, field.label(), normalizedOld, normalizedNew, field.compareWith(), changes);
                return;
            }

            compare(path, field.label(), normalizeNullAsEmpty(oldFieldValue), normalizeNullAsEmpty(newFieldValue), depth + 1, changes);
        }

        private Object readField(FieldMeta field, Object target, String path) {
            try {
                return field.getValue(target);
            } catch (FieldMeta.FieldReadException e) {
                throw new CompareAbortException(FieldAccessError.of(path, e.getCause()));
            }
        }

        /**
         * {@code nullAsEmpty=true} 时把空串规整为 null，使 null 与 "" 在后续比较中视为相等
         * （两者都归一后要么同为 null 直接判等，要么其中一侧仍为具体值走 ADDED/REMOVED）。
         */
        private Object normalizeNullAsEmpty(Object value) {
            if (options.nullAsEmpty() && "".equals(value)) {
                return null;
            }
            return value;
        }

        private void compareMap(String path, Map<Object, Object> oldMap, Map<Object, Object> newMap,
                                 int depth, List<FieldChange> changes) {
            Map<Object, Object> safeOld = oldMap == null ? Map.of() : oldMap;
            Map<Object, Object> safeNew = newMap == null ? Map.of() : newMap;
            for (Map.Entry<Object, Object> entry : safeOld.entrySet()) {
                Object key = entry.getKey();
                String elementPath = path + "[" + key + "]";
                if (!safeNew.containsKey(key)) {
                    changes.add(new FieldChange(elementPath, elementPath, ChangeKind.REMOVED, entry.getValue(), null,
                            LeafValues.toText(entry.getValue(), datePattern), null));
                }
            }
            for (Map.Entry<Object, Object> entry : safeNew.entrySet()) {
                Object key = entry.getKey();
                String elementPath = path + "[" + key + "]";
                if (!safeOld.containsKey(key)) {
                    changes.add(new FieldChange(elementPath, elementPath, ChangeKind.ADDED, null, entry.getValue(),
                            null, LeafValues.toText(entry.getValue(), datePattern)));
                } else {
                    compare(elementPath, elementPath, safeOld.get(key), entry.getValue(), depth + 1, changes);
                }
            }
        }

        private void compareIndexedCollection(String path, Object oldValue, Object newValue, int depth, List<FieldChange> changes) {
            List<Object> oldList = toList(oldValue);
            List<Object> newList = toList(newValue);
            int commonSize = Math.min(oldList.size(), newList.size());
            for (int i = 0; i < commonSize; i++) {
                compare(path + "[" + i + "]", path + "[" + i + "]", oldList.get(i), newList.get(i), depth + 1, changes);
            }
            for (int i = commonSize; i < oldList.size(); i++) {
                Object removed = oldList.get(i);
                String elementPath = path + "[" + i + "]";
                changes.add(new FieldChange(elementPath, elementPath, ChangeKind.REMOVED, removed, null,
                        LeafValues.toText(removed, datePattern), null));
            }
            for (int i = commonSize; i < newList.size(); i++) {
                Object added = newList.get(i);
                String elementPath = path + "[" + i + "]";
                changes.add(new FieldChange(elementPath, elementPath, ChangeKind.ADDED, null, added,
                        null, LeafValues.toText(added, datePattern)));
            }
        }

        private void recordAddedOrRemoved(String path, String label, Object oldValue, Object newValue, List<FieldChange> changes) {
            if (oldValue == null) {
                changes.add(new FieldChange(path, label, ChangeKind.ADDED, null, newValue,
                        null, LeafValues.toText(newValue, datePattern)));
            } else {
                changes.add(new FieldChange(path, label, ChangeKind.REMOVED, oldValue, null,
                        LeafValues.toText(oldValue, datePattern), null));
            }
        }
    }

    private static boolean isComparableTypePair(Class<?> oldType, Class<?> newType) {
        if (oldType.equals(newType)) {
            return true;
        }
        // 同为 List 或同为 Map 的不同实现类（如 ArrayList vs LinkedList）视为可比较。
        return (List.class.isAssignableFrom(oldType) && List.class.isAssignableFrom(newType))
                || (Map.class.isAssignableFrom(oldType) && Map.class.isAssignableFrom(newType));
    }

    @SuppressWarnings("unchecked")
    private static List<Object> toList(Object value) {
        if (value instanceof List<?> list) {
            return (List<Object>) list;
        }
        int length = java.lang.reflect.Array.getLength(value);
        List<Object> result = new ArrayList<>(length);
        for (int i = 0; i < length; i++) {
            result.add(java.lang.reflect.Array.get(value, i));
        }
        return result;
    }
}
