package cn.code91.toolbox.database.observability;

import com.p6spy.engine.spy.P6DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;

import javax.sql.DataSource;
import java.lang.reflect.Field;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link P6spyDataSourceWrappingBeanPostProcessor} 的构造期反射解析与热路径包装行为
 * （beacon ADR-0013 教训：{@code Class.forName}/构造器查找必须在构造期一次性完成，
 * {@code postProcessAfterInitialization} 热路径只做 {@code isInstance}/{@code newInstance}）。
 */
@DisplayName("P6spyDataSourceWrappingBeanPostProcessor")
class P6spyDataSourceWrappingBeanPostProcessorTest {

    @Test
    @DisplayName("构造期解析 P6DataSource 类与构造器并缓存为 final 字段")
    void constructorResolvesAndCachesP6DataSourceClass() throws NoSuchFieldException, IllegalAccessException {
        P6spyDataSourceWrappingBeanPostProcessor bpp = new P6spyDataSourceWrappingBeanPostProcessor();

        Field classField = bpp.getClass().getDeclaredField("p6DataSourceClass");
        classField.setAccessible(true);
        Object cachedClass = classField.get(bpp);

        assertThat(cachedClass).isEqualTo(P6DataSource.class);
    }

    @Test
    @DisplayName("postProcessAfterInitialization 把普通 DataSource 包装为 P6DataSource")
    void wrapsPlainDataSourceIntoP6DataSource() {
        P6spyDataSourceWrappingBeanPostProcessor bpp = new P6spyDataSourceWrappingBeanPostProcessor();
        DataSource plain = new SimpleDriverDataSource();

        Object result = bpp.postProcessAfterInitialization(plain, "dataSource");

        assertThat(result).isInstanceOf(P6DataSource.class);
    }

    @Test
    @DisplayName("已是 P6DataSource（或其子类）的实例不二次包装")
    void doesNotDoubleWrapExistingP6DataSource() {
        P6spyDataSourceWrappingBeanPostProcessor bpp = new P6spyDataSourceWrappingBeanPostProcessor();
        P6DataSource already = new P6DataSource(new SimpleDriverDataSource());

        Object result = bpp.postProcessAfterInitialization(already, "dataSource");

        assertThat(result).isSameAs(already);
    }

    @Test
    @DisplayName("非 DataSource 类型的 bean 原样透传")
    void passesThroughNonDataSourceBeansUnchanged() {
        P6spyDataSourceWrappingBeanPostProcessor bpp = new P6spyDataSourceWrappingBeanPostProcessor();
        Object notADataSource = new Object();

        Object result = bpp.postProcessAfterInitialization(notADataSource, "someBean");

        assertThat(result).isSameAs(notADataSource);
    }
}
