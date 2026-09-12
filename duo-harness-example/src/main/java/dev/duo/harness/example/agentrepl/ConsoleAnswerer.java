package dev.duo.harness.example.agentrepl;

import dev.duo.harness.tools.Answerer;
import dev.duo.harness.tools.InteractionAnswer;
import dev.duo.harness.tools.InteractionRequest;

import java.io.BufferedReader;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.List;

/**
 * 终端回答者（ADR-0008 的 M6 呈现位）：审批请求呈现工具名与参数并读 y/n；
 * 提问请求呈现问题与选项，接受序号选择或自由文本。EOF / Ctrl+C / 空输入
 * 一律 fail-closed（拒绝或未作答）。非线程安全：与 REPL 同线程串行使用。
 */
public final class ConsoleAnswerer implements Answerer {

    /** 回答者来源标识（审计署名）。 */
    public static final String SOURCE = "console";

    private final BufferedReader in;
    private final PrintStream out;

    public ConsoleAnswerer(BufferedReader in, PrintStream out) {
        this.in = in;
        this.out = out;
    }

    @Override
    public InteractionAnswer answer(InteractionRequest request) {
        if (InteractionRequest.KIND_APPROVAL.equals(request.kind())) {
            return answerApproval(request);
        }
        if (InteractionRequest.KIND_QUESTION.equals(request.kind())) {
            return answerQuestion(request);
        }
        return null;
    }

    /** 审批呈现与作答：y = 允许本次，其余/EOF = 拒绝。 */
    private InteractionAnswer answerApproval(InteractionRequest request) {
        out.println("  [待审批] 工具 " + request.subject() + " 请求执行");
        if (!request.detail().isBlank()) {
            out.println("          参数: " + request.detail());
        }
        out.print("  批准本次执行？[y=允许 / n=拒绝] ");
        out.flush();
        String line = readLine();
        if (line == null || line.isBlank()) {
            out.println("  （未作答，按拒绝处理）");
            return InteractionAnswer.failClosed();
        }
        return "y".equalsIgnoreCase(line.strip())
                ? InteractionAnswer.allow(SOURCE)
                : InteractionAnswer.deny(SOURCE);
    }

    /** 提问呈现与作答：有序号选项时输序号（多选逗号分隔），否则自由文本。 */
    private InteractionAnswer answerQuestion(InteractionRequest request) {
        out.println("  [提问] " + request.subject());
        List<String> options = request.options();
        if (!options.isEmpty()) {
            for (int i = 0; i < options.size(); i++) {
                out.println("    " + (i + 1) + ". " + options.get(i));
            }
            out.print(request.multiSelect()
                    ? "  请选择（序号，多选逗号分隔；或直接输入你的回答）: "
                    : "  请选择（输序号；或直接输入你的回答）: ");
        } else {
            out.print("  你的回答: ");
        }
        out.flush();
        String line = readLine();
        if (line == null || line.isBlank()) {
            out.println("  （未作答）");
            return InteractionAnswer.failClosed();
        }
        return InteractionAnswer.answered(resolveValues(line.strip(), options, request.multiSelect()), SOURCE);
    }

    /** 序号 → 选项文本；非序号输入按自由文本原样采用。 */
    private List<String> resolveValues(String input, List<String> options, boolean multiSelect) {
        if (options.isEmpty()) {
            return List.of(input);
        }
        List<String> values = new ArrayList<>();
        for (String part : input.split("[,，]")) {
            String token = part.strip();
            Integer index = tryParseIndex(token, options.size());
            values.add(index != null ? options.get(index - 1) : token);
        }
        if (!multiSelect && values.size() > 1) {
            return List.of(values.get(0));
        }
        return values;
    }

    /** 纯数字且落在选项范围时返回序号，否则 null（按自由文本）。 */
    private Integer tryParseIndex(String token, int optionCount) {
        if (!token.matches("\\d+")) {
            return null;
        }
        int index = Integer.parseInt(token);
        return index >= 1 && index <= optionCount ? index : null;
    }

    private String readLine() {
        try {
            return in.readLine();
        } catch (java.io.IOException e) {
            return null;
        }
    }
}
