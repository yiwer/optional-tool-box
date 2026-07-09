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
import java.util.function.Supplier;

/**
 * 反射驱动的差异引擎实现。遍历规则见 01 设计文档 §4.3，集合范围按控制器裁定 B 收窄为
 * BY_INDEX（List/数组）与 key 配对（Map），不实现 BY_KEY/AS_SET/{@code @CompareKey}。
 */
public final class ReflectionDiffEngine implements DiffEngine {

    private static final String DEFAULT_DATE_PATTERN = "yyyy-MM-dd HH:mm:ss";

    private final CompareHandlerRegistry registry;
    private final ClassMetaCache metaCache = new ClassMetaCache();
    private final String datePattern;
    private final DiffOptions defaultOptions;

    public ReflectionDiffEngine(CompareHandlerRegistry registry) {
        this(registry, DEFAULT_DATE_PATTERN);
    }

    public ReflectionDiffEngine(CompareHandlerRegistry registry, String datePattern) {
        this(registry, datePattern, DiffOptions.defaults());
    }

    /**
     * @param defaultOptions 无显式 {@link DiffOptions} 参数的 {@link #diff(Object, Object)} 重载所采用的默认选项
     *                        （对接 {@code toolbox.compare.max-depth}/{@code null-as-empty} 等配置）。
     */
    public ReflectionDiffEngine(CompareHandlerRegistry registry, String datePattern, DiffOptions defaultOptions) {
        this.registry = Objects.requireNonNull(registry, "registry cannot be null");
        this.datePattern = Objects.requireNonNull(datePattern, "datePattern cannot be null");
        this.defaultOptions = Objects.requireNonNull(defaultOptions, "defaultOptions cannot be null");
    }

    @Override
    public <T> Result<DiffResult, CompareError> diff(T oldValue, T newValue) {
        return diff(oldValue, newValue, defaultOptions);
    }

    @Override
    public <T> Result<DiffResult, CompareError> diff(T oldValue, T newValue, DiffOptions options) {
        Objects.requireNonNull(options, "options cannot be null");
        Traversal traversal = new Traversal(options);
        try {
            List<FieldChange> changes = new ArrayList<>();
            traversal.compare("", () -> null, oldValue, newValue, 0, changes);
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
         * @param path          当前字段路径（顶层为空串）
         * @param labelSupplier 展示标签的惰性求值（顶层为 null，不产出顶层自身的 FieldChange）；
         *                      仅在实际产出 FieldChange 时才会被调用（I1 修复：{@code @CompareLabel}
         *                      的 messageKey 解析依赖调用时 locale，不应对递归展开、未变更的字段
         *                      也重复解析）
         * @param oldValue      旧值
         * @param newValue      新值
         * @param depth         当前递归深度（顶层对象为 0）
         */
        void compare(String path, Supplier<String> labelSupplier, Object oldValue, Object newValue, int depth, List<FieldChange> changes) {
            if (oldValue == null && newValue == null) {
                return;
            }
            if (oldValue == null || newValue == null) {
                recordAddedOrRemoved(path, labelSupplier, oldValue, newValue, changes);
                return;
            }

            Class<?> oldType = oldValue.getClass();
            Class<?> newType = newValue.getClass();
            if (!isComparableTypePair(oldType, newType)) {
                throw new CompareAbortException(TypeMismatch.of(path, oldType, newType));
            }

            ValueComparator<Object> customComparator = findTypeComparator(oldType);
            if (LeafValues.isLeaf(oldType) || customComparator != null) {
                compareLeaf(path, labelSupplier, oldValue, newValue, customComparator, changes);
                return;
            }
            if (oldValue instanceof Map<?, ?> || newValue instanceof Map<?, ?>) {
                compareMap(path, asObjectMap(oldValue), asObjectMap(newValue), depth, changes);
                return;
            }
            if (oldType.isArray() || oldValue instanceof List<?>) {
                compareIndexedCollection(path, oldValue, newValue, depth, changes);
                return;
            }
            // P1 集合范围收窄为 List/数组/Map（裁定 B），Set/Queue 等其余 Collection 形态在
            // 进入对象图前显式拒绝（I2 修复）：放行到 compareObjectGraph 会把 HashSet 这类容器
            // 当普通 JavaBean 反射展开，钻入 java.base 内部字段（如 HashSet#map）触发
            // InaccessibleObjectException，且即便侥幸可达也不是用户想要的语义（Set 无序，
            // 逐字段对比没有意义）。
            if (oldValue instanceof java.util.Collection<?> || newValue instanceof java.util.Collection<?>) {
                Class<?> unsupportedType = oldValue instanceof java.util.Collection<?> ? oldType : newType;
                throw new CompareAbortException(new FieldAccessError(path,
                        "P1 集合仅支持 List/数组/Map，遇到 " + unsupportedType.getName()
                                + " 类型请转换或 @CompareIgnore"));
            }
            compareObjectGraph(path, oldValue, newValue, depth, changes);
        }

        /**
         * 类型级比较器按运行时 Class 查找；注册表内部以 {@code Class<?>} 为键，
         * 取出的比较器与 {@code type} 实参在运行时天然匹配，强转安全。
         */
        @SuppressWarnings("unchecked")
        private ValueComparator<Object> findTypeComparator(Class<?> type) {
            return registry.findComparator((Class<Object>) type);
        }

        /**
         * Map 字段在反射读取时已知其声明类型为 {@code Map<?,?>}；key 相同则值走同一套
         * 递归 compare，运行时不关心具体键值类型，强转到 {@code Map<Object,Object>} 仅为消除签名噪音。
         */
        @SuppressWarnings("unchecked")
        private Map<Object, Object> asObjectMap(Object value) {
            return (Map<Object, Object>) value;
        }

        /**
         * 展示文本渲染：应用注册的类型级 {@link cn.code91.toolbox.compare.spi.ValueFormatter} 优先，
         * 未注册时回退 {@link LeafValues#toText} 内置规则（日期时间走 facility DateUtil，其余 toString）。
         */
        @SuppressWarnings("unchecked")
        private String renderText(Object value) {
            if (value == null) {
                return null;
            }
            var formatter = registry.findFormatter((Class<Object>) value.getClass());
            return formatter != null ? formatter.format(value) : LeafValues.toText(value, datePattern);
        }

        /**
         * 字段级 {@code @CompareWith} 优先，其次应用/内置类型级比较器，最后 {@code Objects.equals} 兜底。
         */
        private void compareLeaf(String path, Supplier<String> labelSupplier, Object oldValue, Object newValue,
                                  ValueComparator<Object> typeLevelComparator, List<FieldChange> changes) {
            boolean equal = typeLevelComparator != null
                    ? typeLevelComparator.isEqual(oldValue, newValue)
                    : LeafValues.equalsLeaf(oldValue, newValue);
            if (equal) {
                return;
            }
            changes.add(new FieldChange(path, labelSupplier.get(), ChangeKind.MODIFIED, oldValue, newValue,
                    renderText(oldValue), renderText(newValue)));
        }

        /**
         * 字段级 {@code @CompareWith} 比较器实例覆盖叶子/类型级判断，供 {@link #compareField} 调用。
         */
        private void compareLeafWithFieldOverride(String path, Supplier<String> labelSupplier, Object oldValue, Object newValue,
                                                   ValueComparator<Object> fieldComparator, List<FieldChange> changes) {
            if (fieldComparator.isEqual(oldValue, newValue)) {
                return;
            }
            changes.add(new FieldChange(path, labelSupplier.get(), ChangeKind.MODIFIED, oldValue, newValue,
                    renderText(oldValue), renderText(newValue)));
        }

        /**
         * 深度上限 + 环检测防线（01 §4.3 的对象图全局保障）：对象图的每一种递归展开边——
         * 嵌套对象、Map value、List/数组元素——进入前都必须过这道检查，并把容器/对象本身
         * 登记进递归栈；否则纯集合自引用（List 含自身、Map value 引用回自身）会绕过防线
         * 导致 StackOverflowError。与 {@link #exitGraphNode} 必须成对（finally 出栈），
         * 保证兄弟节点复用同一实例时不会误报环。
         */
        private void enterGraphNode(String path, Object oldValue, Object newValue, int depth) {
            if (depth >= options.maxDepth()) {
                throw new CompareAbortException(DepthExceeded.of(path, options.maxDepth()));
            }
            if (recursionStack.containsKey(oldValue) || recursionStack.containsKey(newValue)) {
                throw new CompareAbortException(CycleDetected.of(path));
            }
            recursionStack.put(oldValue, Boolean.TRUE);
            recursionStack.put(newValue, Boolean.TRUE);
        }

        private void exitGraphNode(Object oldValue, Object newValue) {
            recursionStack.remove(oldValue);
            recursionStack.remove(newValue);
        }

        private void compareObjectGraph(String path, Object oldValue, Object newValue, int depth, List<FieldChange> changes) {
            enterGraphNode(path, oldValue, newValue, depth);
            try {
                for (FieldMeta field : fieldsOf(path, oldValue.getClass())) {
                    compareField(path, field, oldValue, newValue, depth, changes);
                }
            } finally {
                exitGraphNode(oldValue, newValue);
            }
        }

        /**
         * {@code metaCache.fieldsOf} 内部对未见过的类做反射元数据解析（getter 句柄绑定），
         * 遇到无公开 getter 的类型（如 {@code java.util.Date}/{@code UUID} 落入
         * {@code ClassMetaCache} 的字段直接访问兜底分支）会触发 {@code field.setAccessible(true)}，
         * 对 JDK 内部类字段（{@code java.base} 未 {@code opens} 给未命名模块）抛
         * {@code InaccessibleObjectException}——一个未受检 {@link RuntimeException}，任其穿透
         * 违反本引擎"结构性错误一律走 Result#err"的契约（I2 修复）。此处收敛为带路径的
         * {@link FieldAccessError}。{@code ClassMetaCache} 自身只抛 {@code IllegalStateException}/
         * JDK 反射异常，不引用本类的 {@link CompareAbortException}，故此处宽 catch 不会误吞
         * 深度超限/环检测/类型不一致等已在 {@link #compare} 中以更具体错误类型抛出的信号。
         */
        private List<FieldMeta> fieldsOf(String path, Class<?> type) {
            try {
                return metaCache.fieldsOf(type);
            } catch (RuntimeException e) {
                throw new CompareAbortException(FieldAccessError.of(path, e));
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
                    recordAddedOrRemoved(path, field::resolveLabel, normalizedOld, normalizedNew, changes);
                    return;
                }
                compareLeafWithFieldOverride(path, field::resolveLabel, normalizedOld, normalizedNew, field.compareWith(), changes);
                return;
            }

            compare(path, field::resolveLabel, normalizeNullAsEmpty(oldFieldValue), normalizeNullAsEmpty(newFieldValue), depth + 1, changes);
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

        /**
         * oldMap/newMap 均由 {@link #compare} 保证非 null（双侧任一为 null 时已在上一层
         * 归为 ADDED/REMOVED 提前返回，不会走到这里），因此无需再做 null 兜底。
         * Map 本身作为对象图节点入栈：value 引用回该 Map 时经 {@link #enterGraphNode} 报环。
         */
        private void compareMap(String path, Map<Object, Object> oldMap, Map<Object, Object> newMap,
                                 int depth, List<FieldChange> changes) {
            enterGraphNode(path, oldMap, newMap, depth);
            try {
                for (Map.Entry<Object, Object> entry : oldMap.entrySet()) {
                    Object key = entry.getKey();
                    String elementPath = path + "[" + key + "]";
                    if (!newMap.containsKey(key)) {
                        changes.add(new FieldChange(elementPath, elementPath, ChangeKind.REMOVED, entry.getValue(), null,
                                renderText(entry.getValue()), null));
                    }
                }
                for (Map.Entry<Object, Object> entry : newMap.entrySet()) {
                    Object key = entry.getKey();
                    String elementPath = path + "[" + key + "]";
                    if (!oldMap.containsKey(key)) {
                        changes.add(new FieldChange(elementPath, elementPath, ChangeKind.ADDED, null, entry.getValue(),
                                null, renderText(entry.getValue())));
                    } else {
                        compare(elementPath, () -> elementPath, oldMap.get(key), entry.getValue(), depth + 1, changes);
                    }
                }
            } finally {
                exitGraphNode(oldMap, newMap);
            }
        }

        /**
         * 入栈登记必须用原始 oldValue/newValue（数组经 {@link #toList} 会被包一层新 ArrayList，
         * 若登记包装列表，元素引用回原容器时按 identity 查不到、环检测失效）。
         */
        private void compareIndexedCollection(String path, Object oldValue, Object newValue, int depth, List<FieldChange> changes) {
            enterGraphNode(path, oldValue, newValue, depth);
            try {
                List<Object> oldList = toList(oldValue);
                List<Object> newList = toList(newValue);
                int commonSize = Math.min(oldList.size(), newList.size());
                for (int i = 0; i < commonSize; i++) {
                    String elementPath = path + "[" + i + "]";
                    compare(elementPath, () -> elementPath, oldList.get(i), newList.get(i), depth + 1, changes);
                }
                for (int i = commonSize; i < oldList.size(); i++) {
                    Object removed = oldList.get(i);
                    String elementPath = path + "[" + i + "]";
                    changes.add(new FieldChange(elementPath, elementPath, ChangeKind.REMOVED, removed, null,
                            renderText(removed), null));
                }
                for (int i = commonSize; i < newList.size(); i++) {
                    Object added = newList.get(i);
                    String elementPath = path + "[" + i + "]";
                    changes.add(new FieldChange(elementPath, elementPath, ChangeKind.ADDED, null, added,
                            null, renderText(added)));
                }
            } finally {
                exitGraphNode(oldValue, newValue);
            }
        }

        private void recordAddedOrRemoved(String path, Supplier<String> labelSupplier, Object oldValue, Object newValue, List<FieldChange> changes) {
            if (oldValue == null) {
                changes.add(new FieldChange(path, labelSupplier.get(), ChangeKind.ADDED, null, newValue,
                        null, renderText(newValue)));
            } else {
                changes.add(new FieldChange(path, labelSupplier.get(), ChangeKind.REMOVED, oldValue, null,
                        renderText(oldValue), null));
            }
        }
    }

    private static boolean isComparableTypePair(Class<?> oldType, Class<?> newType) {
        if (oldType.equals(newType)) {
            return true;
        }
        // 带方法体的枚举常量运行时类是合成匿名子类（E$1/E$2），不同常量类不同；
        // 同一声明枚举下的常量互为可比较（P2：带方法体枚举）。
        if (Enum.class.isAssignableFrom(oldType) && Enum.class.isAssignableFrom(newType)) {
            return baseEnumClass(oldType).equals(baseEnumClass(newType));
        }
        // 同为 List 或同为 Map 的不同实现类（如 ArrayList vs LinkedList）视为可比较。
        return (List.class.isAssignableFrom(oldType) && List.class.isAssignableFrom(newType))
                || (Map.class.isAssignableFrom(oldType) && Map.class.isAssignableFrom(newType));
    }

    /**
     * 枚举常量的声明枚举类：普通常量运行时类即声明类（isEnum()=true）；带方法体常量的
     * 运行时类是匿名子类（isEnum()=false），其直接父类才是声明枚举类。
     */
    private static Class<?> baseEnumClass(Class<?> type) {
        return type.isEnum() ? type : type.getSuperclass();
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
