package com.ai.guide.architecture;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RestController;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * COLA 4.0 架构看门狗测试 (Architecture Guard Dog)
 *
 * 核心目标：
 * 作为自动化测试与 CI/CD 流水线的物理级防护网，用代码硬约束全工程依赖单向性，
 * 永久杜绝开发者随手 Alt+Enter 或赶需求时无意引入跨层违规反向 import。
 *
 * 报错信息已全面中文化、语义化，并附带精确的错误原因与修复操作指引。
 */
@AnalyzeClasses(packages = "com.ai.guide", importOptions = { ImportOption.DoNotIncludeTests.class })
public class ColaArchitectureGuardTest {

    /**
     * 规则 1：领域层绝对纯洁度守卫 (Domain Layer Purity Guard)
     * 领域层为系统的核心资产，严禁依赖适配层、应用层或基础设施层。
     */
    @ArchTest
    public static final ArchRule domain_layer_must_not_depend_on_other_layers =
            noClasses()
                    .that().resideInAPackage("com.ai.guide.domain..")
                    .should().dependOnClassesThat().resideInAnyPackage(
                            "com.ai.guide.adapter..",
                            "com.ai.guide.application..",
                            "com.ai.guide.infrastructure.."
                    )
                    .because("\n" +
                            "╔═══════════════════════════════════════════════════════════════════════════════════════════╗\n" +
                            "║ 【COLA 4.0 核心红线违规】领域层 (domain) 必须保持 100% 架构纯洁度！                          ║\n" +
                            "╠═══════════════════════════════════════════════════════════════════════════════════════════╣\n" +
                            "║ 错误原因：领域层代码直接引用了 adapter / application / infrastructure 包中的类。           ║\n" +
                            "║                                                                                           ║\n" +
                            "║ 👉 修复指引（三种标准解决方案）：                                                          ║\n" +
                            "║ 1. 若需调用数据库/Redis/外部第三方 API（如高德、百度）：                                  ║\n" +
                            "║    -> 请在 domain.<模块>.gateway 包下声明领域网关端口接口 (Gateway Port)；                ║\n" +
                            "║    -> 在 infrastructure.<模块>.. 下实现该接口（遵循依赖倒置原则 DIP）。                   ║\n" +
                            "║ 2. 若需入参或业务命令对象：                                                               ║\n" +
                            "║    -> 请将该 DTO/Command 实体沉淀至 domain.<模块>.model 包中，严禁依赖应用层 DTO。          ║\n" +
                            "║ 3. 请检查 IDE 是否通过快捷键（如 Alt+Enter）自动误导入了外层实现类，请立即撤销并改为依赖接口。║\n" +
                            "╚═══════════════════════════════════════════════════════════════════════════════════════════╝");

    /**
     * 规则 2：基础设施层单向依赖守卫 (Infrastructure Dependency Guard)
     * 基础设施层只能向下支撑领域层（实现网关端口），严禁反向依赖适配层或应用层。
     */
    @ArchTest
    public static final ArchRule infrastructure_layer_must_not_depend_on_adapter_or_application =
            noClasses()
                    .that().resideInAPackage("com.ai.guide.infrastructure..")
                    .should().dependOnClassesThat().resideInAnyPackage(
                            "com.ai.guide.adapter..",
                            "com.ai.guide.application.."
                    )
                    .because("\n" +
                            "╔═══════════════════════════════════════════════════════════════════════════════════════════╗\n" +
                            "║ 【COLA 4.0 架构违规】基础设施层 (infrastructure) 严禁反向依赖适配层或应用层！             ║\n" +
                            "╠═══════════════════════════════════════════════════════════════════════════════════════════╣\n" +
                            "║ 错误原因：基础设施实现类直接引用了 Controller 或 ApplicationService。                     ║\n" +
                            "║                                                                                           ║\n" +
                            "║ 👉 修复指引：                                                                              ║\n" +
                            "║ 基础设施层只负责提供技术支撑（DB、Cache、RPC、HTTP）。若需数据，应通过领域模型传递，       ║\n" +
                            "║ 绝不可向上反向依赖上层的控制器或应用编排服务。                                             ║\n" +
                            "╚═══════════════════════════════════════════════════════════════════════════════════════════╝");

    /**
     * 规则 3：适配层防越级穿透守卫 (Adapter Anti-Bypass Guard)
     * 适配层（如 Web 控制器）严禁越过应用层直接依赖基础设施层。
     */
    @ArchTest
    public static final ArchRule adapter_layer_must_not_depend_on_infrastructure =
            noClasses()
                    .that().resideInAPackage("com.ai.guide.adapter..")
                    .should().dependOnClassesThat().resideInAPackage("com.ai.guide.infrastructure..")
                    .because("\n" +
                            "╔═══════════════════════════════════════════════════════════════════════════════════════════╗\n" +
                            "║ 【COLA 4.0 架构违规】适配层 (adapter) 严禁越级穿透依赖基础设施层 (infrastructure)！        ║\n" +
                            "╠═══════════════════════════════════════════════════════════════════════════════════════════╣\n" +
                            "║ 错误原因：Web 控制器直接引用了具体的 Repository 实现类、Redis 客户端或外部 SDK 客户端。    ║\n" +
                            "║                                                                                           ║\n" +
                            "║ 👉 修复指引：                                                                              ║\n" +
                            "║ 适配层只负责协议转换（HTTP -> 内部命令），所有业务逻辑必须委派给应用层 (ApplicationService)║\n" +
                            "║ 统一编排，严禁在控制器中直接读写数据库或操纵 Redis。                                       ║\n" +
                            "╚═══════════════════════════════════════════════════════════════════════════════════════════╝");

    /**
     * 规则 4：Web 控制器物理归属守卫 (Controller Location Guard)
     * 所有的 Spring Web 控制器必须统一归入 com.ai.guide.adapter.. 包下。
     */
    @ArchTest
    public static final ArchRule controllers_must_reside_in_adapter_layer =
            classes()
                    .that().areAnnotatedWith(RestController.class)
                    .or().areAnnotatedWith(Controller.class)
                    .should().resideInAPackage("com.ai.guide.adapter..")
                    .because("\n" +
                            "╔═══════════════════════════════════════════════════════════════════════════════════════════╗\n" +
                            "║ 【COLA 4.0 规范违规】所有的 Web 控制器必须放置在适配层 com.ai.guide.adapter.. 包下！     ║\n" +
                            "╠═══════════════════════════════════════════════════════════════════════════════════════════╣\n" +
                            "║ 错误原因：检测到 @RestController 或 @Controller 类被放置在了非 adapter 目录下。           ║\n" +
                            "║                                                                                           ║\n" +
                            "║ 👉 修复指引：                                                                              ║\n" +
                            "║ 请将该 Controller 迁移至 com.ai.guide.adapter.<模块>.web 包下，严禁分散在 domain 或其他层。 ║\n" +
                            "╚═══════════════════════════════════════════════════════════════════════════════════════════╝");

    /**
     * 规则 5：领域网关端口必须是接口 (Gateway Port Interface Guard)
     * 在 domain.<模块>.gateway 包下声明的端口必须是纯粹的 Java Interface。
     */
    @ArchTest
    public static final ArchRule domain_gateway_ports_must_be_interfaces =
            classes()
                    .that().resideInAPackage("com.ai.guide.domain..gateway..")
                    .and().areTopLevelClasses()
                    .should().beInterfaces()
                    .because("\n" +
                            "╔═══════════════════════════════════════════════════════════════════════════════════════════╗\n" +
                            "║ 【DIP 依赖倒置规范违规】领域网关端口 (domain.gateway) 必须为纯 Interface 接口！            ║\n" +
                            "╠═══════════════════════════════════════════════════════════════════════════════════════════╣\n" +
                            "║ 错误原因：在 domain..gateway 包中创建了具体类（Class）而不是接口。                        ║\n" +
                            "║                                                                                           ║\n" +
                            "║ 👉 修复指引：                                                                              ║\n" +
                            "║ 领域层仅负责定义标准操作契约端口 (interface)，具体的技术实现必须放置在 infrastructure 层。║\n" +
                            "╚═══════════════════════════════════════════════════════════════════════════════════════════╝");

    /**
     * 规则 6：历史遗留 api 包防死灰复燃守卫 (Anti-Zombie API Package Guard)
     * 全系统已 100% 消除 domain.{module}.api 控制器包，严禁在此包路径下新建任何类。
     */
    @ArchTest
    public static final ArchRule no_domain_api_packages_allowed =
            noClasses()
                    .should().resideInAPackage("com.ai.guide.domain..api..")
                    .because("\n" +
                            "╔═══════════════════════════════════════════════════════════════════════════════════════════╗\n" +
                            "║ 【架构防腐红线】严禁在领域层新建 domain..api 包！                                          ║\n" +
                            "╠═══════════════════════════════════════════════════════════════════════════════════════════╣\n" +
                            "║ 错误原因：在领域层发现了 domain..api 包路径。                                             ║\n" +
                            "║                                                                                           ║\n" +
                            "║ 👉 修复指引：                                                                              ║\n" +
                            "║ 重构已彻底清除全系统 12 个模块的所有旧 domain..api 控制器包。所有 Web 端点必须放置在      ║\n" +
                            "║ adapter.<模块>.web 包下，严禁复辟旧目录！                                                  ║\n" +
                            "╚═══════════════════════════════════════════════════════════════════════════════════════════╝");
}
