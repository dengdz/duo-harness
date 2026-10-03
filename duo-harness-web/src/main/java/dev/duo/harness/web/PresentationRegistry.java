package dev.duo.harness.web;

import dev.duo.harness.core.api.Disposable;
import dev.duo.harness.core.api.PluginException;

import java.util.List;
import java.util.Map;

/**
 * 呈现贡献口（ADR-0038 决策四）：插件按<b>申请制</b>注册两类展示面声明——主题
 * （主题 id → token 值集）与展示卡（工具名 → 卡片长相）。与端点命名空间贡献口
 * 同哲学：同名占用即点名拒绝、先到先得（卸了再装即替换语义）、返回的移除器挂
 * 提供方作用域（拔除即注销）。
 *
 * <p>安全模型「改值不改构」：主题只能改变量值、卡片只能声明长相字段——声明经
 * 内核校验（token 值字面量白名单）后由聚合端点 {@code /api/presentation} 序列化
 * 下发，插件没有直达前端的注入通道（不经过贡献端点通道、不携带样式/脚本）。</p>
 *
 * <p>获取：经服务 {@value #SERVICE_NAME}（WebPlugin 发布）。无 Web 部署（headless）
 * 下服务缺席——消费方 {@code optionalInject} 声明 + hasService 守卫优雅缺席
 * （M34 教训：hasService 真 ≠ 可读，声明必须显式）；主题行先于 web 行装载时，
 * 容器依赖指纹在服务后到时自动重载注册（PluginInstance 可选依赖语义）。</p>
 */
public interface PresentationRegistry {

    /** 服务名（WebPlugin 发布；消费方视图接口方法名与此逐字一致）。 */
    String SERVICE_NAME = "presentationRegistry";

    /**
     * 申请主题：themeId 占用即点名拒绝。tokens 的键须为 {@code --xxx} 形态的
     * token 名、值须过字面量白名单（函数值 url() 拒收——堵样式注入缝）。
     *
     * @return 幂等移除器——执行即注销本主题（同 id 可重新申请）
     */
    Disposable claimTheme(ThemeDeclaration theme);

    /**
     * 注册展示卡声明：toolName 占用即点名拒绝（先到先得，卸了再装即替换）。
     *
     * @return 幂等移除器——执行即注销本声明（同工具名可重新注册）
     */
    Disposable registerCard(CardDeclaration card);

    /** 全部已注册主题（聚合端点下发形态；快照副本，注册表后续变化不影响）。 */
    List<ThemeDeclaration> themes();

    /** 全部已注册展示卡声明（聚合端点下发形态；快照副本）。 */
    List<CardDeclaration> cards();

    /**
     * 主题声明：token 值集即整套换肤——键为 {@code --xxx} 命名的 token 名
     * （主题令牌只增不改名，内置 19 token 为契约锚点），值为 CSS 字面量。
     */
    record ThemeDeclaration(String themeId, String displayName, Map<String, String> tokens) {
    }

    /**
     * 展示卡声明：工具调用的展示型卡片长相（图标 / 中文名 / 参数摘要字段名）——
     * 只涉展示、不涉交互语义；审批/提问/计划等交互卡在内核独占面不开放。
     */
    record CardDeclaration(String toolName, String icon, String label, List<String> summaryFields) {
    }

    /** 消费视图（inject / optionalInject 声明闸门内读取；方法名与服务名逐字一致）。 */
    interface PresentationView {

        PresentationRegistry presentationRegistry();
    }

    /** 主题 id 合法性校验：小写字母/数字/连字符（聚合端点路径与前端匹配键）。 */
    static void requireValidThemeId(String themeId) {
        if (themeId == null || !themeId.matches("[a-z][a-z0-9-]*")) {
            throw new PluginException("主题 id 须为小写字母开头的 [a-z0-9-] 形态: " + themeId);
        }
    }
}
