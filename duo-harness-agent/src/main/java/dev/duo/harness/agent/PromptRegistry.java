package dev.duo.harness.agent;

import dev.duo.harness.core.api.Context;
import dev.duo.harness.core.api.Disposable;
import dev.duo.harness.llm.LlmConfig;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * prompt 注册表（"prompts" 服务，ADR-0007 v3 的 M6 挂载点）：插件贡献提示片段，
 * 每轮请求按注册序动态组装最终 system 提示。
 *
 * <p>组装规则：yml 的 {@code llm.systemPrompt}（构造时传入的用户指令）永远排最前，
 * 其后为片段按注册序拼接（空行分隔）；两者皆空时落 {@link LlmConfig#DEFAULT_SYSTEM_PROMPT}。
 * 片段随注册方作用域自动摘除（与工具 / guard / 回答者注册同构）；注册表内容不变则
 * 组装结果不变，provider 前缀缓存不受影响。</p>
 *
 * <p>M7 技能系统：技能指令段经 {@code register} 进注册表即挂载、注销即摘除。</p>
 */
public final class PromptRegistry {

    /** 用户指令片段（yml llm.systemPrompt；可空）。 */
    private final String userPrompt;
    /** 提示片段（注册序；CopyOnWrite 支撑组装时并发摘除）。 */
    private final List<PromptFragment> fragments = new CopyOnWriteArrayList<>();

    /**
     * @param userPrompt 用户指令（yml llm.systemPrompt；null 或空白 = 未配置）
     */
    public PromptRegistry(String userPrompt) {
        this.userPrompt = userPrompt == null || userPrompt.isBlank() ? null : userPrompt;
    }

    /**
     * 注册提示片段，随注册方作用域自动摘除。
     *
     * @param registrant 注册方 Context（其作用域销毁时片段自动摘除）
     * @param fragment   提示片段
     * @return 注销器（手动提前摘除用）
     */
    public Disposable register(Context registrant, PromptFragment fragment) {
        Objects.requireNonNull(registrant, "registrant");
        Objects.requireNonNull(fragment, "fragment");
        // 与 ToolsServiceImpl.register 同模式：先挂注册方生命周期，再入列
        Disposable removal = registrant.effect(() -> fragments.remove(fragment));
        fragments.add(fragment);
        return removal;
    }

    /** 组装最终 system 提示：用户指令最前 + 片段按注册序，全空落内置缺省。 */
    public String compose() {
        StringBuilder composed = new StringBuilder();
        if (userPrompt != null) {
            composed.append(userPrompt);
        }
        for (PromptFragment fragment : fragments) {
            if (composed.length() > 0) {
                composed.append("\n\n");
            }
            composed.append(fragment.content());
        }
        return composed.length() == 0 ? LlmConfig.DEFAULT_SYSTEM_PROMPT : composed.toString();
    }
}
