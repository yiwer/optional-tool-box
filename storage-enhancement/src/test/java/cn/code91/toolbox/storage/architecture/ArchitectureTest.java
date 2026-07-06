package cn.code91.toolbox.storage.architecture;

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
 * 全局约束（00-overview.md §5.4）固定三条规则：包无环；{@code autoconfigure} 不被主包依赖；
 * core/guard 门面不得引用可选 SDK 类型（{@code software.amazon.**}/{@code com.aliyun.**}），
 * 保证缺 SDK 时门面可加载（任务简报明确要求的第三条 ArchUnit 规则的具体形态）。
 */
class ArchitectureTest {

    private static final JavaClasses CLASSES = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("cn.code91.toolbox.storage");

    @Test
    void topLevelSlicesShouldBeFreeOfCycles() {
        ArchRule rule = slices().assignedFrom(new TopLevelPackageSliceAssignment()).should().beFreeOfCycles();

        rule.check(CLASSES);
    }

    @Test
    void autoconfigurePackageShouldNotBeDependedOnByOtherPackages() {
        ArchRule rule = ArchRuleDefinition.noClasses()
                .that().resideOutsideOfPackage("cn.code91.toolbox.storage.autoconfigure..")
                .should().dependOnClassesThat().resideInAPackage("cn.code91.toolbox.storage.autoconfigure..");

        rule.check(CLASSES);
    }

    @Test
    void coreAndGuardShouldNotDependOnOptionalCloudSdkTypes() {
        ArchRule rule = ArchRuleDefinition.noClasses()
                .that().resideInAnyPackage("cn.code91.toolbox.storage.core..", "cn.code91.toolbox.storage.guard..")
                .should().dependOnClassesThat()
                .resideInAnyPackage("software.amazon.awssdk..", "com.aliyun..");

        rule.check(CLASSES);
    }

    /**
     * 顶层包 → slice 名映射：各顶层包独立成 slice（core/guard/aws/aliyun/local/autoconfigure）。
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
            String prefix = "cn.code91.toolbox.storage.";
            if (!packageName.startsWith(prefix)) {
                return null;
            }
            String remainder = packageName.substring(prefix.length());
            int dotIndex = remainder.indexOf('.');
            return dotIndex == -1 ? remainder : remainder.substring(0, dotIndex);
        }

        @Override
        public String getDescription() {
            return "顶层包（core/guard/aws/aliyun/local/autoconfigure 各自独立）";
        }
    }
}
