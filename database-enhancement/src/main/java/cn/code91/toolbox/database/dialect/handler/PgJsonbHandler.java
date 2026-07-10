package cn.code91.toolbox.database.dialect.handler;

import cn.code91.facility.error.WrappedError;
import cn.code91.facility.json.Jsons;
import cn.code91.facility.json.support.JsonConfig;
import cn.code91.facility.result.Result;
import cn.code91.toolbox.database.spi.FieldHandler;
import org.postgresql.util.PGobject;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Objects;
import java.util.Set;

/**
 * <b>PG jsonb/json handler</b>
 * <p>参数化 per 目标 Java 类型，经 {@link cn.code91.toolbox.database.registry.FieldHandlerRegistry}
 * 的 {@code bySqlTypeFactory} 按需构造（同一 handler 类要服务任意 POJO 目标类型，无法预先
 * 注册一个具体实例）。</p>
 *
 * <p><b>只用 facility 库 API，不注入 bean</b>（约束 6，学 beacon ADR-0024）：自持一份
 * {@link Jsons}（{@link JsonConfig#canonical()} 底座 + {@code includeAlways()} 覆盖），
 * 不通过 Spring 依赖注入——保证本 handler 在无 Spring 容器的纯 JVM 场景（如单测直接
 * {@code new}）也能工作，且不给"数据库模块"添加对"JSON 模块 Spring 装配"的隐性依赖。</p>
 *
 * <p><b>序列化 inclusion 必须 ALWAYS（存储保真修复）</b>：曾直接取 {@code CANONICAL}
 * 命名空间，其 {@code NON_EMPTY} inclusion 会把空集合/空 Map/空串<b>属性整键丢弃</b>——
 * 空 field_changes 落库变 {@code {}}、回读缺键退化为 null，空/null 语义被破坏
 * （回归钉：{@code PgJsonbHandlerTest} 空集合两例 + {@code PgHandlerRoundTripIT} 真库往返）。
 * 落库通道的取舍与 API 输出通道不同：文档/响应可以省流，持久化必须往返保真，故保留
 * canonical 的其余特质（java8 时间、忽略未知键、日期字符串化、键排序=jsonb 确定性）而
 * 仅覆盖 inclusion。</p>
 *
 * <p>{@link Jsons#serialize}/{@link Jsons#deserialize} 返回 facility 的 {@code Result}——这是
 * facility 库 API 的自然形状，与"数据访问路径不用 Result"（约束 3）不矛盾：本类在内部把
 * {@code Result} 立即摊平为受检异常 {@link SQLException}，对外暴露的 {@link #read}/{@link #write}
 * 签名与其余全部 handler 一致，调用方（{@code EntityRowMapper}/{@code AnnotatedParameterSource}，
 * Task 8）看到的仍是纯异常语义。</p>
 */
public final class PgJsonbHandler<J> implements FieldHandler<J> {

    /**
     * 存储保真档：canonical 底座、inclusion 覆盖为 ALWAYS（类 Javadoc"序列化 inclusion"段）。
     * 静态共享——ObjectMapper 线程安全，本 handler 按目标类型逐个构造（registry factory），
     * 不必每实例重建 mapper。
     */
    private static final Jsons STORAGE_JSONS = new Jsons(JsonConfig.canonical().includeAlways().build());

    private final Class<J> javaType;
    private final Jsons jsons;

    public PgJsonbHandler(Class<J> javaType) {
        this.javaType = Objects.requireNonNull(javaType, "javaType");
        this.jsons = STORAGE_JSONS;
    }

    @Override
    public Class<J> javaType() {
        return javaType;
    }

    @Override
    public Set<String> sqlTypeAliases() {
        return Set.of("jsonb", "json");
    }

    @Override
    public J read(ResultSet rs, int columnIndex) throws SQLException {
        String json = rs.getString(columnIndex);
        if (json == null || rs.wasNull()) {
            return null;
        }
        Result<J, WrappedError> result = jsons.deserialize(json, javaType);
        return result.orElseThrow(err -> new SQLException(
                "jsonb 反序列化失败（列 " + columnIndex + " -> " + javaType.getSimpleName() + "）",
                err.getException()));
    }

    @Override
    public Object write(J value) throws SQLException {
        if (value == null) {
            return null;
        }
        Result<String, WrappedError> result = jsons.serialize(value);
        String json = result.orElseThrow(err -> new SQLException(
                "jsonb 序列化失败（类型 " + javaType.getSimpleName() + "）", err.getException()));
        PGobject pgObject = new PGobject();
        pgObject.setType("jsonb");
        pgObject.setValue(json);
        return pgObject;
    }
}
