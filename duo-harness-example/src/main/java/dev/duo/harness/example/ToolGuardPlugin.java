package dev.duo.harness.example;

import dev.duo.harness.core.api.Context;
import dev.duo.harness.core.api.Disposable;
import dev.duo.harness.core.api.Plugin;
import dev.duo.harness.core.api.WaterfallListener;
import dev.duo.harness.tools.ToolExecution;
import dev.duo.harness.tools.ToolsService;

/**
 * 管线拦截示例：不侵入工具代码，经三段管线的事件挂点治理——
 * pre-execute 对含 "危险" 的参数否决；post-execute 给结果加治理后缀。
 * （监听器注册即本插件作用域副作用。）
 */
public final class ToolGuardPlugin implements Plugin<Void> {

    @Override
    public Class<Void> configType() {
        return null;
    }

    @Override
    public Disposable apply(Context ctx, Void config) {
        Disposable pre = ctx.on(ToolsService.PRE_EXECUTE,
                (WaterfallListener<ToolExecution, Boolean>) (exec, next) -> {
                    if ("echo".equals(exec.toolName())
                            && exec.args().path("input").asText("").contains("危险")) {
                        exec.deny("参数含敏感词（治理插件否决）");
                        return false;
                    }
                    return next.invoke(exec);
                });
        Disposable post = ctx.on(ToolsService.POST_EXECUTE,
                (WaterfallListener<ToolExecution, Boolean>) (exec, next) -> {
                    if (!exec.resultIsError()) {
                        exec.setResult(exec.result() + " [已治理]");
                    }
                    return next.invoke(exec);
                });
        return () -> {
            pre.dispose();
            post.dispose();
        };
    }
}
