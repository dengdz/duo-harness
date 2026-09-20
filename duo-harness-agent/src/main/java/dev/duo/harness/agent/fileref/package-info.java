/**
 * @file 路径提及（M21，ADR-0022 决策 7）：grammar（@token 识别与 mention 格式化）、
 * 工作区路径索引（懒遍历、排除目录、symlink 不跟随）与补全服务（懒构建缓存 +
 * tool/result 后台重建 + 未命中重建重试）。零内容注入——文件内容永远经 read 工具。
 */
package dev.duo.harness.agent.fileref;
