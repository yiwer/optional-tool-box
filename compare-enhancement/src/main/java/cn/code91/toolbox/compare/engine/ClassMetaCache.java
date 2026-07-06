package cn.code91.toolbox.compare.engine;

import cn.code91.toolbox.compare.annotation.CompareIgnore;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 按类缓存字段/accessor 元数据，同时支持 record（accessor 方法）与普通 JavaBean（getter 方法），
 * 类维度只解析一次。
 */
final class ClassMetaCache {

    private final ConcurrentHashMap<Class<?>, List<FieldMeta>> cache = new ConcurrentHashMap<>();

    List<FieldMeta> fieldsOf(Class<?> type) {
        return cache.computeIfAbsent(type, ClassMetaCache::resolveFields);
    }

    private static List<FieldMeta> resolveFields(Class<?> type) {
        boolean isRecord = type.isRecord();
        List<FieldMeta> result = new ArrayList<>();
        for (Field field : type.getDeclaredFields()) {
            if (field.isSynthetic() || java.lang.reflect.Modifier.isStatic(field.getModifiers())) {
                continue;
            }
            if (field.isAnnotationPresent(CompareIgnore.class)) {
                continue;
            }
            MethodHandle getter = isRecord ? recordAccessorHandle(type, field) : beanGetterHandle(type, field);
            String label = LabelResolver.resolve(field);
            var compareWith = FieldMeta.resolveCompareWith(field);
            result.add(new FieldMeta(field.getName(), label, getter, compareWith));
        }
        return List.copyOf(result);
    }

    private static MethodHandle recordAccessorHandle(Class<?> type, Field field) {
        try {
            Method accessor = type.getMethod(field.getName());
            return MethodHandles.publicLookup().unreflect(accessor);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("无法解析 record accessor：" + type.getName() + "#" + field.getName(), e);
        }
    }

    private static MethodHandle beanGetterHandle(Class<?> type, Field field) {
        String capitalized = Character.toUpperCase(field.getName().charAt(0)) + field.getName().substring(1);
        boolean isBooleanPrimitive = field.getType() == boolean.class;
        String[] candidateNames = isBooleanPrimitive
                ? new String[]{"is" + capitalized, "get" + capitalized}
                : new String[]{"get" + capitalized};
        for (String candidate : candidateNames) {
            try {
                Method getter = type.getMethod(candidate);
                return MethodHandles.publicLookup().unreflect(getter);
            } catch (NoSuchMethodException ignored) {
                // try next candidate
            } catch (IllegalAccessException e) {
                throw new IllegalStateException(
                        "无法解析 JavaBean getter：" + type.getName() + "#" + field.getName(), e);
            }
        }
        try {
            // 兜底：字段本身 public，直接取字段句柄（极少见，但避免因缺 getter 而整体解析失败）
            field.setAccessible(true);
            return MethodHandles.publicLookup().unreflectGetter(field);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(
                    "无法解析 JavaBean getter：" + type.getName() + "#" + field.getName(), e);
        }
    }
}
