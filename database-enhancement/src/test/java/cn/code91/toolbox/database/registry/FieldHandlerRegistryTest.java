package cn.code91.toolbox.database.registry;

import cn.code91.toolbox.database.registry.handler.EnumByNameHandler;
import cn.code91.toolbox.database.spi.FieldHandler;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link FieldHandlerRegistry} 三索引查找优先级、alias 不反盖、freeze、宽松写路径回退。
 * 覆盖任务简报明确列出的全部场景（Task 7 brief 测试小节）。
 */
@DisplayName("FieldHandlerRegistry")
class FieldHandlerRegistryTest {

    enum Color {RED, GREEN}

    /** 最小 String handler：验证 byJavaType 精确匹配。 */
    static final class StringHandler implements FieldHandler<String> {
        @Override
        public Class<String> javaType() {
            return String.class;
        }

        @Override
        public String read(ResultSet rs, int columnIndex) throws SQLException {
            return rs.getString(columnIndex);
        }

        @Override
        public Object write(String value) {
            return value;
        }
    }

    /** 最小 Integer handler：验证 primitive 装箱。 */
    static final class IntegerHandler implements FieldHandler<Integer> {
        @Override
        public Class<Integer> javaType() {
            return Integer.class;
        }

        @Override
        public Integer read(ResultSet rs, int columnIndex) throws SQLException {
            return rs.getInt(columnIndex);
        }

        @Override
        public Object write(Integer value) {
            return value;
        }
    }

    /** 带 alias 的 String handler：模拟 inet/uuid 等"javaType 复用但需要方言别名精确路由"的场景。 */
    static final class AliasStringHandler implements FieldHandler<String> {
        @Override
        public Class<String> javaType() {
            return String.class;
        }

        @Override
        public Set<String> sqlTypeAliases() {
            return Set.of("inet");
        }

        @Override
        public String read(ResultSet rs, int columnIndex) throws SQLException {
            return rs.getString(columnIndex);
        }

        @Override
        public Object write(String value) {
            return value;
        }
    }

    @Nested
    @DisplayName("findHandler 查找优先级")
    class FindHandlerPriority {

        @Test
        @DisplayName("精确 Java 类型命中")
        void exactMatch() {
            FieldHandlerRegistry registry = new FieldHandlerRegistry();
            registry.register(new StringHandler());

            assertThat(registry.findHandler(String.class)).get().isInstanceOf(StringHandler.class);
        }

        @Test
        @DisplayName("primitive int 装箱命中 Integer handler")
        void primitiveBoxing() {
            FieldHandlerRegistry registry = new FieldHandlerRegistry();
            registry.register(new IntegerHandler());

            assertThat(registry.findHandler(int.class)).get().isInstanceOf(IntegerHandler.class);
        }

        @Test
        @DisplayName("枚举类型未注册专属 handler 时兜底 EnumByNameHandler")
        void enumFallback() {
            FieldHandlerRegistry registry = new FieldHandlerRegistry();

            assertThat(registry.findHandler(Color.class)).get().isInstanceOf(EnumByNameHandler.class);
        }

        @Test
        @DisplayName("assignable 扫描：子类型命中父类型已注册的 handler")
        void assignableScan() {
            FieldHandlerRegistry registry = new FieldHandlerRegistry();
            FieldHandler<CharSequence> csHandler = new FieldHandler<>() {
                @Override
                public Class<CharSequence> javaType() {
                    return CharSequence.class;
                }

                @Override
                public CharSequence read(ResultSet rs, int columnIndex) throws SQLException {
                    return rs.getString(columnIndex);
                }

                @Override
                public Object write(CharSequence value) {
                    return value;
                }
            };
            registry.register(csHandler);

            // StringBuilder 未直接注册，但 CharSequence.isAssignableFrom(StringBuilder) 为真
            assertThat(registry.findHandler(StringBuilder.class)).get().isSameAs(csHandler);
        }

        @Test
        @DisplayName("未注册且非 enum/primitive 装箱可解 → empty")
        void notFound() {
            FieldHandlerRegistry registry = new FieldHandlerRegistry();

            assertThat(registry.findHandler(Object.class)).isEmpty();
        }
    }

    @Nested
    @DisplayName("alias 路由与不反盖")
    class AliasRouting {

        @Test
        @DisplayName("带 alias 的 handler 不覆盖 byJavaType 中已有的通用 handler")
        void aliasDoesNotOverwriteExistingJavaTypeHandler() {
            FieldHandlerRegistry registry = new FieldHandlerRegistry();
            StringHandler generic = new StringHandler();
            registry.register(generic);
            registry.register(new AliasStringHandler());

            // byJavaType[String] 仍是先注册的通用 handler，未被 alias handler 反盖
            assertThat(registry.findHandler(String.class)).get().isSameAs(generic);
            // 但经 alias 路由能拿到 AliasStringHandler
            assertThat(registry.findHandlerForSqlType("inet", String.class)).get().isInstanceOf(AliasStringHandler.class);
        }

        @Test
        @DisplayName("javaType 未被占用时，带 alias 的 handler 正常进入 byJavaType")
        void aliasHandlerEntersJavaTypeIndexWhenSlotIsFree() {
            FieldHandlerRegistry registry = new FieldHandlerRegistry();
            registry.register(new AliasStringHandler());

            assertThat(registry.findHandler(String.class)).get().isInstanceOf(AliasStringHandler.class);
        }

        @Test
        @DisplayName("registerFactory + findHandlerForSqlType 参数化路由")
        void factoryRouting() {
            FieldHandlerRegistry registry = new FieldHandlerRegistry();
            registry.registerFactory("jsonb", type -> new AliasStringHandler());

            assertThat(registry.findHandlerForSqlType("jsonb", String.class)).isPresent();
        }

        @Test
        @DisplayName("未知 alias → empty")
        void unknownAliasReturnsEmpty() {
            FieldHandlerRegistry registry = new FieldHandlerRegistry();

            assertThat(registry.findHandlerForSqlType("does-not-exist", String.class)).isEmpty();
        }
    }

    @Nested
    @DisplayName("freeze")
    class Freeze {

        @Test
        @DisplayName("freeze 前 isFrozen 为 false")
        void notFrozenInitially() {
            assertThat(new FieldHandlerRegistry().isFrozen()).isFalse();
        }

        @Test
        @DisplayName("freeze 后 isFrozen 为 true")
        void frozenAfterFreeze() {
            FieldHandlerRegistry registry = new FieldHandlerRegistry();
            registry.freeze();

            assertThat(registry.isFrozen()).isTrue();
        }

        @Test
        @DisplayName("freeze 后 register 抛 IllegalStateException")
        void frozenRejectsRegister() {
            FieldHandlerRegistry registry = new FieldHandlerRegistry();
            registry.freeze();

            assertThatThrownBy(() -> registry.register(new StringHandler()))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("frozen");
        }

        @Test
        @DisplayName("freeze 后 registerFactory 抛 IllegalStateException")
        void frozenRejectsRegisterFactory() {
            FieldHandlerRegistry registry = new FieldHandlerRegistry();
            registry.freeze();

            assertThatThrownBy(() -> registry.registerFactory("jsonb", type -> null))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("frozen");
        }
    }

    @Nested
    @DisplayName("registerBuiltins")
    class RegisterBuiltins {

        @Test
        @DisplayName("注册全部标准 handler：常见 Java 类型均可查到")
        void registersAllStandardHandlers() {
            FieldHandlerRegistry registry = new FieldHandlerRegistry();
            FieldHandlerRegistry.registerBuiltins(registry);

            assertThat(registry.findHandler(String.class)).isPresent();
            assertThat(registry.findHandler(Short.class)).isPresent();
            assertThat(registry.findHandler(Integer.class)).isPresent();
            assertThat(registry.findHandler(Long.class)).isPresent();
            assertThat(registry.findHandler(java.math.BigDecimal.class)).isPresent();
            assertThat(registry.findHandler(Boolean.class)).isPresent();
            assertThat(registry.findHandler(java.time.LocalDate.class)).isPresent();
            assertThat(registry.findHandler(java.time.LocalDateTime.class)).isPresent();
            assertThat(registry.findHandler(java.time.OffsetDateTime.class)).isPresent();
            assertThat(registry.findHandler(java.util.UUID.class)).isPresent();
            assertThat(registry.findHandler(byte[].class)).isPresent();
        }
    }

    @Nested
    @DisplayName("Customizer")
    class Customizer {

        @Test
        @DisplayName("customizer（FunctionalInterface 实现）在 freeze 前注入自定义 handler")
        void customizerInjectsCustomHandlerBeforeFreeze() {
            FieldHandlerRegistry registry = new FieldHandlerRegistry();
            StringHandler custom = new StringHandler();
            FieldHandlerRegistryCustomizer customizer = reg -> reg.register(custom);

            customizer.customize(registry);
            registry.freeze();

            assertThat(registry.findHandler(String.class)).get().isSameAs(custom);
        }
    }
}
