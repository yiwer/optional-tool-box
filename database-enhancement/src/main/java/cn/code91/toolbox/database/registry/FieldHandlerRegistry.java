package cn.code91.toolbox.database.registry;

import cn.code91.toolbox.database.registry.handler.BigDecimalHandler;
import cn.code91.toolbox.database.registry.handler.BooleanHandler;
import cn.code91.toolbox.database.registry.handler.ByteArrayHandler;
import cn.code91.toolbox.database.registry.handler.EnumByNameHandler;
import cn.code91.toolbox.database.registry.handler.IntegerHandler;
import cn.code91.toolbox.database.registry.handler.LocalDateHandler;
import cn.code91.toolbox.database.registry.handler.LocalDateTimeHandler;
import cn.code91.toolbox.database.registry.handler.LongHandler;
import cn.code91.toolbox.database.registry.handler.OffsetDateTimeHandler;
import cn.code91.toolbox.database.registry.handler.ShortHandler;
import cn.code91.toolbox.database.registry.handler.StringHandler;
import cn.code91.toolbox.database.registry.handler.UuidHandler;
import cn.code91.toolbox.database.spi.FieldHandler;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

import static java.util.Map.entry;

/**
 * <b>FieldHandler 三索引注册表</b>
 * <p>{@code byJavaType} + {@code bySqlTypeAlias} + {@code bySqlTypeFactory} 三索引，启动期
 * {@link #freeze()} 后运行期不可再修改（线程安全：{@code freeze} 用 volatile 标记，
 * 三个索引本身用 {@link ConcurrentHashMap}，读路径全程无锁）。</p>
 *
 * <h3>findHandler 查找优先级</h3>
 * <ol>
 *   <li>精确 Java 类型命中 {@code byJavaType}</li>
 *   <li>primitive 装箱后再查（如 {@code int.class} → {@code Integer.class}）</li>
 *   <li>枚举类型兜底：未注册专属 handler 时动态构造 {@link EnumByNameHandler}
 *       （不预注册进 {@code byJavaType}，因为具体枚举类型无法在 {@link #registerBuiltins} 阶段穷举）</li>
 *   <li>assignable 扫描：遍历 {@code byJavaType} 找"声明类型是待查类型父类/接口"的第一个匹配
 *       （用于抽象基类场景，如自定义值对象的公共父接口）</li>
 * </ol>
 *
 * <h3>alias 不反盖 byJavaType（beacon ADR-0023 的教训）</h3>
 * <p>同一 Java 类型可能有多种 SQL 表示（如 {@code String} 既可以是普通 {@code varchar}，
 * 也可以经 {@code @Column(sqlType="inet")} 路由为 PG {@code inet}）。若"带 alias 的特化 handler"
 * 注册时不假思索地覆盖 {@code byJavaType[String]}，会导致所有未显式声明 {@code sqlType} 的
 * {@code String} 字段全部被特化 handler 接管——这既不符合直觉也难以调试。规则：{@link #register}
 * 时若该 alias handler 的 {@code javaType} 已被占用，则仅进入 {@code bySqlTypeAlias} 索引，
 * 不覆盖 {@code byJavaType} 中已有的通用 handler；只有 slot 空闲时才顺带占位。</p>
 *
 * <h3>写路径宽松回退</h3>
 * <p>{@link #findHandler} 未命中时返回 {@link Optional#empty()}，调用方（Task 8 的
 * {@code AnnotatedParameterSource}）据此回退到裸值交给 Spring 默认绑定——不因未覆盖类型而报错，
 * 容忍 registry 未穷举到的类型（beacon R3）。</p>
 */
public final class FieldHandlerRegistry {

    private static final Map<Class<?>, Class<?>> PRIMITIVE_TO_WRAPPER = Map.ofEntries(
            entry(boolean.class, Boolean.class),
            entry(byte.class, Byte.class),
            entry(short.class, Short.class),
            entry(int.class, Integer.class),
            entry(long.class, Long.class),
            entry(float.class, Float.class),
            entry(double.class, Double.class),
            entry(char.class, Character.class));

    private final Map<Class<?>, FieldHandler<?>> byJavaType = new ConcurrentHashMap<>();
    private final Map<String, FieldHandler<?>> bySqlTypeAlias = new ConcurrentHashMap<>();
    private final Map<String, Function<Class<?>, FieldHandler<?>>> bySqlTypeFactory = new ConcurrentHashMap<>();
    private volatile boolean frozen = false;

    /**
     * 注册一个 handler。若其声明了 {@link FieldHandler#sqlTypeAliases()} 且对应 javaType 的
     * {@code byJavaType} slot 已被占用，则仅经 alias 路由可达（不反盖，见类文档）。
     */
    public <J> void register(FieldHandler<J> handler) {
        ensureNotFrozen(handler.javaType().getName());
        if (handler.sqlTypeAliases().isEmpty() || !byJavaType.containsKey(handler.javaType())) {
            byJavaType.put(handler.javaType(), handler);
        }
        for (String alias : handler.sqlTypeAliases()) {
            bySqlTypeAlias.put(alias, handler);
        }
    }

    /**
     * 注册一个参数化 handler 工厂（如 jsonb handler 需按目标 Java 类型动态构造）。
     */
    public void registerFactory(String sqlTypeAlias, Function<Class<?>, FieldHandler<?>> factory) {
        ensureNotFrozen(sqlTypeAlias);
        bySqlTypeFactory.put(sqlTypeAlias, factory);
    }

    private void ensureNotFrozen(String name) {
        if (frozen) {
            throw new IllegalStateException("FieldHandlerRegistry is frozen; cannot register: " + name);
        }
    }

    /**
     * 按 Java 类型查找 handler，见类文档"findHandler 查找优先级"。
     */
    @SuppressWarnings("unchecked")
    public <J> Optional<FieldHandler<J>> findHandler(Class<J> javaType) {
        FieldHandler<?> exact = byJavaType.get(javaType);
        if (exact != null) {
            return Optional.of((FieldHandler<J>) exact);
        }

        if (javaType.isPrimitive()) {
            Class<?> wrapper = PRIMITIVE_TO_WRAPPER.get(javaType);
            if (wrapper != null) {
                FieldHandler<?> boxed = byJavaType.get(wrapper);
                if (boxed != null) {
                    return Optional.of((FieldHandler<J>) boxed);
                }
            }
        }

        if (javaType.isEnum()) {
            @SuppressWarnings("rawtypes")
            Class enumType = javaType;
            return Optional.of((FieldHandler<J>) new EnumByNameHandler(enumType));
        }

        for (Map.Entry<Class<?>, FieldHandler<?>> entry : byJavaType.entrySet()) {
            if (entry.getKey().isAssignableFrom(javaType)) {
                return Optional.of((FieldHandler<J>) entry.getValue());
            }
        }
        return Optional.empty();
    }

    /**
     * 按方言类型别名（{@code @Column.sqlType}）精确路由：{@code bySqlTypeAlias} 精确命中优先，
     * 否则尝试 {@code bySqlTypeFactory} 按目标 Java 类型参数化构造。
     */
    @SuppressWarnings("unchecked")
    public <J> Optional<FieldHandler<J>> findHandlerForSqlType(String sqlTypeAlias, Class<J> javaType) {
        FieldHandler<?> exact = bySqlTypeAlias.get(sqlTypeAlias);
        if (exact != null) {
            return Optional.of((FieldHandler<J>) exact);
        }
        Function<Class<?>, FieldHandler<?>> factory = bySqlTypeFactory.get(sqlTypeAlias);
        if (factory != null) {
            return Optional.of((FieldHandler<J>) factory.apply(javaType));
        }
        return Optional.empty();
    }

    /** 冻结：此后 {@link #register}/{@link #registerFactory} 抛 {@link IllegalStateException}。 */
    public void freeze() {
        this.frozen = true;
    }

    public boolean isFrozen() {
        return frozen;
    }

    /**
     * 注册全部标准 handler（String/各数值/Boolean/日期时间 java.time 全套/byte[]/UUID）。
     * 枚举类型不在此注册——由 {@link #findHandler} 的 {@code isEnum()} 分支 per-field 动态构造
     * {@link EnumByNameHandler}（具体枚举类型无法穷举）。
     */
    public static void registerBuiltins(FieldHandlerRegistry registry) {
        registry.register(new StringHandler());
        registry.register(new ShortHandler());
        registry.register(new IntegerHandler());
        registry.register(new LongHandler());
        registry.register(new BigDecimalHandler());
        registry.register(new BooleanHandler());
        registry.register(new LocalDateHandler());
        registry.register(new LocalDateTimeHandler());
        registry.register(new OffsetDateTimeHandler());
        registry.register(new UuidHandler());
        registry.register(new ByteArrayHandler());
    }
}
