package cn.code91.toolbox.database.naming;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("ColumnNamingStrategy")
class ColumnNamingStrategyTest {

    @Test
    @DisplayName("CAMEL_TO_UNDERSCORE: userName -> user_name")
    void camelToUnderscoreConvertsSimpleField() {
        assertThat(ColumnNamingStrategy.CAMEL_TO_UNDERSCORE.toColumnName("userName")).isEqualTo("user_name");
    }

    @Test
    @DisplayName("CAMEL_TO_UNDERSCORE: 首字母大写不产生前导下划线")
    void camelToUnderscoreDoesNotPrefixLeadingUppercase() {
        assertThat(ColumnNamingStrategy.CAMEL_TO_UNDERSCORE.toColumnName("Id")).isEqualTo("id");
    }

    @Test
    @DisplayName("CAMEL_TO_UNDERSCORE: 连续大写逐字符插入下划线")
    void camelToUnderscoreHandlesConsecutiveUppercase() {
        assertThat(ColumnNamingStrategy.CAMEL_TO_UNDERSCORE.toColumnName("userID")).isEqualTo("user_i_d");
    }

    @Test
    @DisplayName("CAMEL_TO_UNDERSCORE: 空串/null 原样返回")
    void camelToUnderscoreHandlesEmptyAndNull() {
        assertThat(ColumnNamingStrategy.CAMEL_TO_UNDERSCORE.toColumnName("")).isEmpty();
        assertThat(ColumnNamingStrategy.CAMEL_TO_UNDERSCORE.toColumnName(null)).isNull();
    }

    @Test
    @DisplayName("IDENTITY: 原样返回")
    void identityReturnsInputUnchanged() {
        assertThat(ColumnNamingStrategy.IDENTITY.toColumnName("userName")).isEqualTo("userName");
    }
}
