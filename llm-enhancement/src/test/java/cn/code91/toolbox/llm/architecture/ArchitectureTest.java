package cn.code91.toolbox.llm.architecture;

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
 * core/spi/prompt 不得引用 {@code cn.code91.toolbox.llm.openai.**} 具体类或
 * {@code org.springframework.web.client.**}/{@code org.springframework.http.**} 等 HTTP
 * 客户端类型——保证门面（{@code LlmClient}/{@code LlmClientRegistry}/{@code PromptTemplates}）
 * 在不引入任何 HTTP 客户端实现细节的前提下即可独立加载（裁定 E：openai adapter 是唯一持有
 * RestClient 类型的地方）。
 */
class ArchitectureTest {

    private static final JavaClasses CLASSES = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("cn.code91.toolbox.llm");

    @Test
    void topLevelSlicesShouldBeFreeOfCycles() {
        ArchRule rule = slices().assignedFrom(new TopLevelPackageSliceAssignment()).should().beFreeOfCycles();

        rule.check(CLASSES);
    }

    @Test
    void autoconfigurePackageShouldNotBeDependedOnByOtherPackages() {
        ArchRule rule = ArchRuleDefinition.noClasses()
                .that().resideOutsideOfPackage("cn.code91.toolbox.llm.autoconfigure..")
                .should().dependOnClassesThat().resideInAPackage("cn.code91.toolbox.llm.autoconfigure..");

        rule.check(CLASSES);
    }

    @Test
    void coreSpiAndPromptShouldNotDependOnOpenAiOrHttpClientTypes() {
        ArchRule rule = ArchRuleDefinition.noClasses()
                .that().resideInAnyPackage(
                        "cn.code91.toolbox.llm.core..",
                        "cn.code91.toolbox.llm.spi..",
                        "cn.code91.toolbox.llm.prompt..")
                .should().dependOnClassesThat()
                .resideInAnyPackage(
                        "cn.code91.toolbox.llm.openai..",
                        "org.springframework.web.client..",
                        "org.springframework.http..");

        rule.check(CLASSES);
    }

    /**
     * 顶层包 → slice 名映射：各顶层包独立成 slice（core/spi/prompt/openai/autoconfigure）。
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
            String prefix = "cn.code91.toolbox.llm.";
            if (!packageName.startsWith(prefix)) {
                return null;
            }
            String remainder = packageName.substring(prefix.length());
            int dotIndex = remainder.indexOf('.');
            return dotIndex == -1 ? remainder : remainder.substring(0, dotIndex);
        }

        @Override
        public String getDescription() {
            return "顶层包（core/spi/prompt/openai/autoconfigure 各自独立）";
        }
    }
}
