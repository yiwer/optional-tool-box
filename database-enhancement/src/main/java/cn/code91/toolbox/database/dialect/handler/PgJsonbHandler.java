package cn.code91.toolbox.database.dialect.handler;

import cn.code91.facility.error.WrappedError;
import cn.code91.facility.json.JsonUtil;
import cn.code91.facility.json.Jsons;
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
 * <p><b>只用 facility 库 API，不注入 bean</b>（约束 6，学 beacon ADR-0024）：直接调用
 * {@link JsonUtil#use(String)} 静态门面取 {@code CANONICAL} 命名空间的 {@link Jsons}，
 * 不通过 Spring 依赖注入——保证本 handler 在无 Spring 容器的纯 JVM 场景（如单测直接
 * {@code new}）也能工作，且不给"数据库模块"添加对"JSON 模块 Spring 装配"的隐性依赖。</p>
 *
 * <p>{@link Jsons#serialize}/{@link Jsons#deserialize} 返回 facility 的 {@code Result}——这是
 * facility 库 API 的自然形状，与"数据访问路径不用 Result"（约束 3）不矛盾：本类在内部把
 * {@code Result} 立即摊平为受检异常 {@link SQLException}，对外暴露的 {@link #read}/{@link #write}
 * 签名与其余全部 handler 一致，调用方（{@code EntityRowMapper}/{@code AnnotatedParameterSource}，
 * Task 8）看到的仍是纯异常语义。</p>
 */
public final class PgJsonbHandler<J> implements FieldHandler<J> {

    private final Class<J> javaType;
    private final Jsons jsons;

    public PgJsonbHandler(Class<J> javaType) {
        this.javaType = Objects.requireNonNull(javaType, "javaType");
        this.jsons = JsonUtil.use(JsonUtil.CANONICAL);
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
