package dev.duo.harness.tools.fs;

/**
 * 视觉闸门回填能力（M28 工单 03，H-01 类型下探消解）：呈现位装配按 {@code llm.vision}
 * 回填视觉闸门时按本接口探测——read_image 换实现或第三方替换只要实现本接口即被
 * 正确回填，装配层不再下探具体工具类。
 */
public interface VisionGateAware {

    /** 回填视觉闸门（缺省 false，装配后按配置回填；重复回填幂等无副作用）。 */
    void setVisionGate(java.util.function.BooleanSupplier gate);
}
