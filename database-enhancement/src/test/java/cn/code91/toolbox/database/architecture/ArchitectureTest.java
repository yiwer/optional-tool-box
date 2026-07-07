package cn.code91.toolbox.database.architecture;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition;
import com.tngtech.archunit.library.dependencies.SliceAssignment;
import com.tngtech.archunit.library.dependencies.SliceIdentifier;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

/**
 * 全局约束（00-overview.md §5.4，M3 计划 Global Constraints 约束 8）固定三条规则：包无环；
 * {@code autoconfigure} 不被主包依赖；{@code spi}/{@code dialect} 门面（不含
 * {@code dialect.handler} 子包）不得引用 {@code com.p6spy.**}/{@code org.postgresql.**} 类型，
 * 保证缺可选依赖时门面仍可加载。
 *
 * <p><b>已披露的规则范围裁定</b>：brief 原文措辞是"core/spi/dialect 门面"，但本模块的包结构
 * （见 brief 交付物清单）没有 {@code core} 包——领域概念（注解/命名/SPI/注册表/方言）已分散在
 * {@code annotation}/{@code naming}/{@code spi}/{@code registry}/{@code dialect} 五个顶层包，
 * 故第三条规则的检查范围对应到 {@code spi} 与 {@code dialect}（不含
 * {@code dialect.handler}——PG 特化 handler 依规则明确允许引用驱动类型）。{@code registry}
 * 包本身也不引用驱动类型（其标准 handler 只用 JDK/{@code java.sql} 标准 API），
 * 未额外强制加入本规则检查范围，但实际状态天然满足。</p>
 */
class ArchitectureTest {

    private static final JavaClasses CLASSES = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("cn.code91.toolbox.database");

    @Test
    void topLevelSlicesShouldBeFreeOfCycles() {
        ArchRule rule = slices().assignedFrom(new TopLevelPackageSliceAssignment()).should().beFreeOfCycles();

        rule.check(CLASSES);
    }

    @Test
    void autoconfigurePackageShouldNotBeDependedOnByOtherPackages() {
        ArchRule rule = ArchRuleDefinition.noClasses()
                .that().resideOutsideOfPackage("cn.code91.toolbox.database.autoconfigure..")
                .should().dependOnClassesThat().resideInAPackage("cn.code91.toolbox.database.autoconfigure..");

        rule.check(CLASSES);
    }

    @Test
    void spiAndDialectFacadeShouldNotDependOnOptionalDriverTypes() {
        ArchRule rule = ArchRuleDefinition.noClasses()
                .that().resideInAPackage("cn.code91.toolbox.database.spi..")
                .or().resideInAnyPackage("cn.code91.toolbox.database.dialect")
                .should().dependOnClassesThat()
                .resideInAnyPackage("com.p6spy..", "org.postgresql..");

        rule.check(CLASSES);
    }

    /**
     * 顶层包 → slice 名映射：各顶层包独立成 slice
     * （annotation/naming/spi/registry/dialect/observability/autoconfigure）。
     */
    private static final class TopLevelPackageSliceAssignment implements SliceAssignment {

        @Override
        public SliceIdentifier getIdentifierOf(JavaClass javaClass) {
            String topLevelPackage = topLevelPackageOf(javaClass);
            if (topLevelPackage == null) {
                return SliceIdentifier.ignore();
            }
            return SliceIdentifier.of(topLevelPackage);
        }

        private static String topLevelPackageOf(JavaClass javaClass) {
            String packageName = javaClass.getPackageName();
            String prefix = "cn.code91.toolbox.database.";
            if (!packageName.startsWith(prefix)) {
                return null;
            }
            String remainder = packageName.substring(prefix.length());
            int dotIndex = remainder.indexOf('.');
            return dotIndex == -1 ? remainder : remainder.substring(0, dotIndex);
        }

        @Override
        public String getDescription() {
            return "顶层包（annotation/naming/spi/registry/dialect/observability/autoconfigure 各自独立）";
        }
    }
}
