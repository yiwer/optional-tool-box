package cn.code91.toolbox.database.registry;

/**
 * <b>装配期扩展点：freeze 前定制 {@link FieldHandlerRegistry}</b>
 * <p>实现并暴露为 {@code @Bean}，{@code ToolboxDatabaseAutoConfiguration} 在 freeze 前
 * 收集全部 customizer 依次调用。与直接暴露 {@code FieldHandler<?>} bean 的区别：customizer
 * 可执行批量注册、条件注册等更复杂的定制逻辑（而不仅是单个 handler 的声明式暴露）。</p>
 *
 * <p><b>包放置的已披露裁定</b>：brief 原文把本接口归在 {@code spi} 包，但 {@code spi} 包只应
 * 含 {@link cn.code91.toolbox.database.spi.FieldHandler} 这一个不依赖 registry 的纯叶子抽象
 * ——本接口的方法签名直接引用 {@link FieldHandlerRegistry}，若放进 {@code spi} 会与
 * {@code registry}（其依赖 {@code spi.FieldHandler}）形成双向依赖环，违反约束 8 的"包无环"
 * ArchUnit 规则。beacon-database 的对应类型 {@code PgHandlerRegistryCustomizer} 同样放在
 * {@code registry} 包（而非 {@code spi}）——本次调整是照搬该证明有效的先例，非另起炉灶。</p>
 */
@FunctionalInterface
public interface FieldHandlerRegistryCustomizer {

    void customize(FieldHandlerRegistry registry);
}
