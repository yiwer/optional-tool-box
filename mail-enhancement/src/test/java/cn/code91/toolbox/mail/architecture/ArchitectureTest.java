package cn.code91.toolbox.mail.architecture;

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
 * 全局约束 8 固定三条规则：包无环；{@code autoconfigure} 不被主包依赖；
 * core/spi/template 不得引用 {@code jakarta.mail.**} 或 {@code org.springframework.mail.**}
 * ——Seam 与值对象必须在缺 starter-mail 时可加载（starter-mail 为 optional，L1 条件装配的前提）。
 */
class ArchitectureTest {

    private static final JavaClasses CLASSES = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("cn.code91.toolbox.mail");

    @Test
    void topLevelSlicesShouldBeFreeOfCycles() {
        ArchRule rule = slices().assignedFrom(new TopLevelPackageSliceAssignment()).should().beFreeOfCycles();

        rule.check(CLASSES);
    }

    @Test
    void autoconfigurePackageShouldNotBeDependedOnByOtherPackages() {
        ArchRule rule = ArchRuleDefinition.noClasses()
                .that().resideOutsideOfPackage("cn.code91.toolbox.mail.autoconfigure..")
                .should().dependOnClassesThat().resideInAPackage("cn.code91.toolbox.mail.autoconfigure..");

        rule.check(CLASSES);
    }

    @Test
    void coreSpiAndTemplateShouldNotDependOnOptionalMailEngineTypes() {
        ArchRule rule = ArchRuleDefinition.noClasses()
                .that().resideInAnyPackage(
                        "cn.code91.toolbox.mail.core..",
                        "cn.code91.toolbox.mail.spi..",
                        "cn.code91.toolbox.mail.template..")
                .should().dependOnClassesThat()
                .resideInAnyPackage("jakarta.mail..", "org.springframework.mail..");

        rule.check(CLASSES);
    }

    /**
     * 顶层包 → slice 名映射：各顶层包独立成 slice
     * （core/spi/template/guard/sandbox/smtp/autoconfigure）。
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
            String prefix = "cn.code91.toolbox.mail.";
            if (!packageName.startsWith(prefix)) {
                return null;
            }
            String remainder = packageName.substring(prefix.length());
            int dotIndex = remainder.indexOf('.');
            return dotIndex == -1 ? remainder : remainder.substring(0, dotIndex);
        }

        @Override
        public String getDescription() {
            return "顶层包（core/spi/template/guard/sandbox/smtp/autoconfigure 各自独立）";
        }
    }
}
