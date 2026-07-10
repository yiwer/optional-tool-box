package cn.code91.toolbox.docs.schema;

import cn.code91.facility.log.LogUtil;
import cn.code91.facility.web.response.BaseResponse;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.type.TypeFactory;
import io.swagger.v3.core.converter.AnnotatedType;
import io.swagger.v3.core.converter.ModelConverter;
import io.swagger.v3.core.converter.ModelConverterContext;
import io.swagger.v3.core.jackson.TypeNameResolver;
import io.swagger.v3.oas.models.media.Schema;

import java.util.Iterator;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * {@code BaseResponse<X>} schema 命名修饰（06 §4.4 契约感知②，裁定 C-2）：把 swagger 默认的
 * 冗长命名（如 {@code BaseResponseUserDto}）修饰为 {@code {X 的 swagger 默认命名片段}Response}
 * （如 {@code UserDtoResponse}/{@code ListUserDtoResponse}）。只动名字不动字段结构——现代
 * springdoc 对方法签名上的具体参数化泛型本就解析正确（06 §1 技术澄清）。
 *
 * <p>机制：在转换链前端把 {@link AnnotatedType#name(String)} 置为修饰名——swagger
 * {@code ModelResolver} 对非空 {@code annotatedType.getName()} 直接采用（实测 2.2.47 源码）。
 * raw/未参数化类型不动（raw class 精确判等，子类 {@code PageBaseResponse} 由专职转换器处理）；
 * 解析失败或命名冲突（不同泛型参数修饰出同名）退回默认命名 + {@code LogUtil} 告警，
 * <b>不产生错误值、不抛异常</b>（06 §4.2 收窄的前提）。
 */
public class BaseResponseModelConverter implements ModelConverter {

    private final Class<?> targetRawClass;
    private final String suffix;

    /** 修饰名 → 首个认领它的类型规范名；同名不同型即冲突（先到者得名，后到退回默认）。 */
    private final ConcurrentMap<String, String> claimedNames = new ConcurrentHashMap<>();

    public BaseResponseModelConverter() {
        this(BaseResponse.class, "Response");
    }

    protected BaseResponseModelConverter(Class<?> targetRawClass, String suffix) {
        this.targetRawClass = targetRawClass;
        this.suffix = suffix;
    }

    @Override
    public Schema resolve(AnnotatedType type, ModelConverterContext context, Iterator<ModelConverter> chain) {
        decorateName(type);
        return chain.hasNext() ? chain.next().resolve(type, context, chain) : null;
    }

    private void decorateName(AnnotatedType annotatedType) {
        JavaType javaType = constructTypeOrNull(annotatedType.getType());
        if (javaType == null || javaType.getRawClass() != targetRawClass || javaType.containedTypeCount() != 1) {
            return;
        }
        if (annotatedType.getName() != null && !annotatedType.getName().isBlank()) {
            return; // 上游已显式命名（如应用注解/其它转换器）不覆盖
        }
        String candidate;
        try {
            candidate = TypeNameResolver.std.nameForType(javaType.containedType(0)) + suffix;
        } catch (RuntimeException ex) {
            LogUtil.warn("{} 的泛型参数命名片段解析失败，退回 swagger 默认命名：{}",
                    javaType.toCanonical(), ex.toString());
            return;
        }
        String canonical = javaType.toCanonical();
        String claimedBy = claimedNames.putIfAbsent(candidate, canonical);
        if (claimedBy != null && !claimedBy.equals(canonical)) {
            LogUtil.warn("schema 命名冲突：{} 与 {} 都会修饰为 {}，后者退回 swagger 默认命名",
                    claimedBy, canonical, candidate);
            return;
        }
        annotatedType.name(candidate);
    }

    /** springdoc/swagger 会喂各种非常规类型：解析不了的必然不是修饰目标，静默交默认链处理。 */
    private static JavaType constructTypeOrNull(java.lang.reflect.Type type) {
        if (type == null) {
            return null;
        }
        try {
            return TypeFactory.defaultInstance().constructType(type);
        } catch (RuntimeException ex) {
            return null;
        }
    }
}
