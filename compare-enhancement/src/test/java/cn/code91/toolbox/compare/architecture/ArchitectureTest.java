package cn.code91.toolbox.compare.architecture;

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
 * 全局约束 8（00-overview.md §5.4）固定三条规则：包无环；{@code autoconfigure} 不被主包依赖；
 * core/spi 门面不依赖装配基础设施（保证门面在缺少 Spring Boot autoconfigure 时仍可独立加载/测试）。
 */
class ArchitectureTest {

    private static final JavaClasses CLASSES = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("cn.code91.toolbox.compare");

    @Test
    void topLevelSlicesShouldBeFreeOfCycles() {
        // core 与 spi 合并为同一个 "seam" slice：00-overview.md §5.4 规则 3 原文即并列称
        // "core/spi 门面"——DiffResult.render(ChangeRenderer) 与 ChangeRenderer.render(DiffResult)
        // 的双向类型引用是裁定 C 明确要求的委托契约，属该门面内部协作而非跨层违规。
        // engine/render/autoconfigure 各自单独成 slice，真正校验实现层之间不应互相成环。
        ArchRule rule = slices().assignedFrom(new SeamMergingSliceAssignment()).should().beFreeOfCycles();

        rule.check(CLASSES);
    }

    @Test
    void autoconfigurePackageShouldNotBeDependedOnByOtherPackages() {
        ArchRule rule = ArchRuleDefinition.noClasses()
                .that().resideOutsideOfPackage("cn.code91.toolbox.compare.autoconfigure..")
                .should().dependOnClassesThat().resideInAPackage("cn.code91.toolbox.compare.autoconfigure..");

        rule.check(CLASSES);
    }

    @Test
    void coreAndSpiShouldNotDependOnAutoconfigureInfrastructure() {
        ArchRule rule = ArchRuleDefinition.noClasses()
                .that().resideInAnyPackage("cn.code91.toolbox.compare.core..", "cn.code91.toolbox.compare.spi..")
                .should().dependOnClassesThat()
                .resideInAnyPackage("cn.code91.toolbox.compare.autoconfigure..", "org.springframework.boot.autoconfigure..");

        rule.check(CLASSES);
    }

    /**
     * 顶层包 → slice 名映射：core/spi 合并为 "seam"，其余顶层包各自独立成 slice。
     */
    private static final class SeamMergingSliceAssignment implements SliceAssignment {

        @Override
        public SliceIdentifier getIdentifierOf(JavaClass javaClass) {
            String topLevelPackage = topLevelPackageOf(javaClass);
            if (topLevelPackage == null) {
                return SliceIdentifier.ignore();
            }
            if (topLevelPackage.equals("core") || topLevelPackage.equals("spi")) {
                return SliceIdentifier.of("seam");
            }
            return SliceIdentifier.of(topLevelPackage);
        }

        private static String topLevelPackageOf(JavaClass javaClass) {
            String packageName = javaClass.getPackageName();
            String prefix = "cn.code91.toolbox.compare.";
            if (!packageName.startsWith(prefix)) {
                return null;
            }
            String remainder = packageName.substring(prefix.length());
            int dotIndex = remainder.indexOf('.');
            return dotIndex == -1 ? remainder : remainder.substring(0, dotIndex);
        }

        @Override
        public String getDescription() {
            return "顶层包（core/spi 合并为 seam）";
        }
    }
}
