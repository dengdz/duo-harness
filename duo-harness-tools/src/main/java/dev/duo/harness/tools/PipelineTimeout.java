package dev.duo.harness.tools;

import com.fasterxml.jackson.databind.JsonNode;
import dev.duo.harness.core.api.Context;
import dev.duo.harness.core.api.Disposable;
import dev.duo.harness.core.api.events.WaterfallListener;

import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 管线缺省超时（ADR-0018）：{@code tools/execute} 段的 around 终端监听器——
 * 任何工具执行最多等 {@code defaultTimeoutMs}，超时即中断执行并以超时错误作为
 * 工具结果回填（沿用异常收敛语义，不炸调用方循环）。
 *
 * <p>超时触发时 interrupt 执行线程（阻塞 IO 可被打断）；不可中断的操作在后台
 * 跑完、结果丢弃——模型已收到超时错误，迟到的真实结果不再采用。优先级：
 * 工具自身声明 &gt; 管线缺省——{@link ToolDefinition#pipelineTimeoutMs} 覆盖本调用
 * 上限（自带协作式超时的工具如 bash 放宽到协作式之上），{@link
 * ToolDefinition#exemptFromPipelineTimeout} 豁免（如 ask_user 等人回答）。</p>
 *
 * <p>挂载即注册方作用域副作用（呈现位装配处调用）；并发调度（工单 M17-01）下
 * 本监听器包裹的是并行池执行线程内的调用——组内任一工具挂死不再积压整轮提交。</p>
 */
public final class PipelineTimeout {

    /** 管线缺省超时上限（与 bash 协作式缺省对齐，ADR-0018 grill Q5 裁定）。 */
    public static final long DEFAULT_TIMEOUT_MS = 120_000;

    /** 执行承载：共享虚拟线程执行器（长生命周期，无核心线程成本）。 */
    private static final ExecutorService EXECUTOR = Executors.newVirtualThreadPerTaskExecutor();

    private PipelineTimeout() {
    }

    /**
     * 挂载管线缺省超时监听器（tools/execute 段 around 终端）。
     *
     * @param registrant      注册方 Context（监听器随其作用域自动摘除）
     * @param tools           工具域服务（查工具定义做豁免 / 覆盖判定）
     * @param defaultTimeoutMs 缺省上限（毫秒，须为正）
     * @return 摘除器（手动摘除用；作用域销毁亦自动摘除）
     */
    public static Disposable mount(Context registrant, ToolsService tools, long defaultTimeoutMs) {
        Objects.requireNonNull(registrant, "registrant");
        Objects.requireNonNull(tools, "tools");
        if (defaultTimeoutMs <= 0) {
            throw new IllegalArgumentException("defaultTimeoutMs 必须为正: " + defaultTimeoutMs);
        }
        return registrant.on(ToolsService.EXECUTE,
                (WaterfallListener<ToolExecution, Boolean>) (exec, next) -> {
                    long timeoutMs = resolveTimeout(tools, exec.toolName(), exec.args(),
                            defaultTimeoutMs);
                    if (timeoutMs < 0) {
                        return next.invoke(exec); // 豁免：不受限时约束
                    }
                    Future<Boolean> running = EXECUTOR.submit(() -> next.invoke(exec));
                    try {
                        return running.get(timeoutMs, TimeUnit.MILLISECONDS);
                    } catch (TimeoutException e) {
                        // interrupt 执行线程；不可中断的让它在后台跑完、结果丢弃
                        running.cancel(true);
                        exec.markTimeout("工具 \"" + exec.toolName() + "\" 执行超时（上限 "
                                + timeoutMs + "ms），已中断；若操作不可中断则其结果被丢弃");
                        return Boolean.TRUE;
                    } catch (Exception e) {
                        // 执行异常原样传播：保持工具域"异常收敛为 error 结果"的既有出口；
                        // 等待自身被中断时同样取消执行线程，不留无主任务
                        running.cancel(true);
                        throw e;
                    }
                });
    }

    /**
     * 本调用的超时上限：豁免返回 -1；覆盖声明优先；否则挂载方给定的缺省。
     * 未知工具（注册表中找不到——理论上 execute 前已查过）按缺省兜底。
     * 声明抛错一律回落缺省——与调度面的并发安全判定同款 fail-closed 防御。
     */
    private static long resolveTimeout(ToolsService tools, String toolName, JsonNode args,
                                       long defaultTimeoutMs) {
        ToolDefinition def = tools.list().stream()
                .filter(d -> d.name().equals(toolName))
                .findFirst()
                .orElse(null);
        if (def == null) {
            return defaultTimeoutMs;
        }
        try {
            if (def.exemptFromPipelineTimeout(args)) {
                return -1;
            }
            Long override = def.pipelineTimeoutMs(args);
            if (override == null || override <= 0) {
                return defaultTimeoutMs;
            }
            return override;
        } catch (Exception e) {
            return defaultTimeoutMs;
        }
    }
}
