package cn.code91.toolbox.docs.architecture;

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
 * 全局约束 8 固定三条规则：包无环；{@code autoconfigure} 不被主包依赖；{@code core} 门面
 * 不得引用 springdoc/swagger 类型（{@code org.springdoc..}/{@code io.swagger..}），保证缺
 * springdoc 时门面可加载（06 §9，同 storage 对可选云 SDK 的第三条规则形态）。
 */
class ArchitectureTest {

    private static final JavaClasses CLASSES = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("cn.code91.toolbox.docs");

    @Test
    void topLevelSlicesShouldBeFreeOfCycles() {
        ArchRule rule = slices().assignedFrom(new TopLevelPackageSliceAssignment()).should().beFreeOfCycles();

        rule.check(CLASSES);
    }

    @Test
    void autoconfigurePackageShouldNotBeDependedOnByOtherPackages() {
        ArchRule rule = ArchRuleDefinition.noClasses()
                .that().resideOutsideOfPackage("cn.code91.toolbox.docs.autoconfigure..")
                .should().dependOnClassesThat().resideInAPackage("cn.code91.toolbox.docs.autoconfigure..");

        rule.check(CLASSES);
    }

    @Test
    void coreShouldNotDependOnSpringdocOrSwaggerTypes() {
        ArchRule rule = ArchRuleDefinition.noClasses()
                .that().resideInAPackage("cn.code91.toolbox.docs.core..")
                .should().dependOnClassesThat()
                .resideInAnyPackage("org.springdoc..", "io.swagger..");

        rule.check(CLASSES);
    }

    /**
     * 顶层包 → slice 名映射：各顶层包独立成 slice（core/grouping/schema/exposure/export/autoconfigure）。
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
            String prefix = "cn.code91.toolbox.docs.";
            if (!packageName.startsWith(prefix)) {
                return null;
            }
            String remainder = packageName.substring(prefix.length());
            int dotIndex = remainder.indexOf('.');
            return dotIndex == -1 ? remainder : remainder.substring(0, dotIndex);
        }

        @Override
        public String getDescription() {
            return "顶层包（core/grouping/schema/exposure/export/autoconfigure 各自独立）";
        }
    }
}
