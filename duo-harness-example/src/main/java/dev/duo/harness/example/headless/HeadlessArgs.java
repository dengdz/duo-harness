package dev.duo.harness.example.headless;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * headless 入口参数解析（M23 工单 07）：{@code --json} 标志 + {@code --session-id <id>}
 * + positional 文本（首段为 .yml/.yaml 文件路径时识别为装配 yml——兼容现状的
 * {@code args[0] = yml} 用法——其余多词拼空格为任务文本）。
 *
 * <p>纯函数无副作用：解析不读文件内容，只做存在性探测（yml 识别）；错误走
 * {@link #errors}（usage error 呈现给 stderr，退出码 2），不抛异常。</p>
 */
public record HeadlessArgs(boolean headless, Path yml, String sessionId, String prompt,
                           List<String> errors) {

    /** 缺省装配 yml（agent-demo.yml 资源路径，与 DuoMain 现状一致）。 */
    public static final String DEFAULT_YML_RESOURCE = "/agent-demo.yml";

    public static HeadlessArgs parse(String[] args) {
        boolean headless = false;
        String sessionId = null;
        Path yml = null;
        List<String> promptWords = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            switch (arg) {
                case "--json" -> headless = true;
                case "--session-id" -> {
                    if (i + 1 < args.length) {
                        sessionId = args[++i];
                    } else {
                        errors.add("--session-id 缺少会话 id 参数");
                    }
                }
                default -> {
                    if (yml == null && promptWords.isEmpty()) {
                        String lower = arg.toLowerCase();
                        boolean ymlSuffix = lower.endsWith(".yml") || lower.endsWith(".yaml");
                        if (isYmlPath(arg)) {
                            yml = Path.of(arg);
                        } else if (ymlSuffix) {
                            // 显式 yml 后缀但文件不存在：明确报错，不静默回退缺省装配
                            errors.add("装配文件不存在: " + arg);
                        } else {
                            promptWords.add(arg);
                        }
                    } else {
                        promptWords.add(arg);
                    }
                }
            }
        }
        String prompt = promptWords.isEmpty() ? null : String.join(" ", promptWords);
        if (headless && (prompt == null || prompt.isBlank())) {
            errors.add("headless --json 需要任务文本（positional），管道消费无任务即退出");
        }
        return new HeadlessArgs(headless, yml, sessionId, prompt, List.copyOf(errors));
    }

    /** yml 识别：后缀 .yml/.yaml 且文件存在（存在性探测，识别为装配文件而非任务词）。 */
    private static boolean isYmlPath(String arg) {
        String lower = arg.toLowerCase();
        return (lower.endsWith(".yml") || lower.endsWith(".yaml")) && Files.isRegularFile(Path.of(arg));
    }

    /** 解析出 yml 路径（未指定时缺省资源），无任务文本等 usage 错误见 {@link #errors}。 */
    public Path effectiveYml() {
        return yml != null ? yml : resourceYml();
    }

    private static Path resourceYml() {
        try {
            return Path.of(HeadlessArgs.class.getResource(DEFAULT_YML_RESOURCE).toURI());
        } catch (Exception e) {
            throw new IllegalStateException("缺省装配资源缺失: " + DEFAULT_YML_RESOURCE, e);
        }
    }
}
