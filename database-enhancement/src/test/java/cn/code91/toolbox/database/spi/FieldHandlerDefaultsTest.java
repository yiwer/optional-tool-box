package cn.code91.toolbox.database.spi;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.ResultSet;
import java.sql.SQLException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link FieldHandler} 的 default 方法契约：{@code sqlTypeAliases()} 未覆盖时为空集合
 * （即"仅按 Java 类型匹配，不路由方言别名"，与 registry 的 alias 不反盖规则配套）。
 */
@DisplayName("FieldHandler 契约")
class FieldHandlerDefaultsTest {

    private static final class MinimalHandler implements FieldHandler<String> {
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

    @Test
    @DisplayName("未覆盖 sqlTypeAliases 时返回空集合")
    void sqlTypeAliasesDefaultsToEmpty() {
        assertThat(new MinimalHandler().sqlTypeAliases()).isEmpty();
    }
}
