package dev.duo.harness.tools.web;

/** 归一化搜索结果条目（ADR-0021 决策 3）：链接 + 标题 + 摘要——provider 差异在此抹平。 */
record SearchSource(String url, String title, String snippet) {
}
