package cn.code91.toolbox.docs.exposure;

import cn.code91.toolbox.docs.core.DocsExposureGate;
import org.springframework.core.env.Environment;

import java.util.Collection;
import java.util.Set;

/**
 * 默认环境门禁（06 §4.5，裁定 D）：激活 profile 与 production-profiles（默认
 * {@code [prod, production]}）有交集 <b>且</b> expose-in-production=false（默认）→ 不暴露；
 * 其余一律暴露。判定入参在构造期由装配层自 {@code toolbox.docs.exposure.*} 抽取下传
 * （ArchUnit 规则 2：本包不得依赖 autoconfigure 的 Properties 类型）。
 */
public class DefaultDocsExposureGate implements DocsExposureGate {

    private final Set<String> productionProfiles;
    private final boolean exposeInProduction;

    public DefaultDocsExposureGate(Collection<String> productionProfiles, boolean exposeInProduction) {
        this.productionProfiles = Set.copyOf(productionProfiles);
        this.exposeInProduction = exposeInProduction;
    }

    @Override
    public boolean isExposed(Environment env) {
        if (exposeInProduction) {
            return true;
        }
        for (String profile : env.getActiveProfiles()) {
            if (productionProfiles.contains(profile)) {
                return false;
            }
        }
        return true;
    }
}
