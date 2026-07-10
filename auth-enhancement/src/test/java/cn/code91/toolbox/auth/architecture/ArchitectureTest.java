package cn.code91.toolbox.auth.architecture;

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
 * 全局约束固定三规则（00 §5.4；第三条按 07 §4.1 调整）：包无环；autoconfigure 不被主包
 * 依赖；core 依赖白名单——允许 spring-security-core（SecurityContextHolder）与
 * oauth2-jose（Jwt 值类型），禁 servlet / security-web / security-config /
 * oauth2-resource-server（AuthContext 判 principal instanceof Jwt，不触
 * JwtAuthenticationToken，依赖面钉死）。
 */
class ArchitectureTest {

    private static final JavaClasses CLASSES = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("cn.code91.toolbox.auth");

    @Test
    void topLevelSlicesShouldBeFreeOfCycles() {
        slices().assignedFrom(new TopLevelPackageSliceAssignment()).should().beFreeOfCycles().check(CLASSES);
    }

    @Test
    void autoconfigurePackageShouldNotBeDependedOnByOtherPackages() {
        ArchRule rule = ArchRuleDefinition.noClasses()
                .that().resideOutsideOfPackage("cn.code91.toolbox.auth.autoconfigure..")
                .should().dependOnClassesThat().resideInAPackage("cn.code91.toolbox.auth.autoconfigure..");
        rule.check(CLASSES);
    }

    @Test
    void coreShouldNotDependOnServletOrSecurityWebTypes() {
        ArchRule rule = ArchRuleDefinition.noClasses()
                .that().resideInAPackage("cn.code91.toolbox.auth.core..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "jakarta.servlet..",
                        "org.springframework.security.web..",
                        "org.springframework.security.config..",
                        "org.springframework.security.oauth2.server.resource..");
        rule.check(CLASSES);
    }

    /** 顶层包 → slice（core/jwt/web/autoconfigure），同 llm 模板。 */
    private static final class TopLevelPackageSliceAssignment implements SliceAssignment {

        @Override
        public SliceIdentifier getIdentifierOf(JavaClass javaClass) {
            String packageName = javaClass.getPackageName();
            String prefix = "cn.code91.toolbox.auth.";
            if (!packageName.startsWith(prefix)) {
                return SliceIdentifier.ignore();
            }
            String rest = packageName.substring(prefix.length());
            int dot = rest.indexOf('.');
            return SliceIdentifier.of(dot < 0 ? rest : rest.substring(0, dot));
        }

        @Override
        public String getDescription() {
            return "cn.code91.toolbox.auth 顶层包切片";
        }
    }
}
