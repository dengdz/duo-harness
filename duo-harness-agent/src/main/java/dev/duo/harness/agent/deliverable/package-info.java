/**
 * 交付声明域（M26 工单 04，ADR-0028）：模型自报成果的语义源头——present 工具
 * 在任务收尾时校验文件真实存在并落 {@code deliverable/presented} 会话事件。
 * 消费方：会话检索索引（按成果文件名反查会话，M26-03 授权边界内）、导出报告
 * 交付清单章节（M26 工单 05）。与系统视角的变更摘要（git 对账）互为印证。
 */
package dev.duo.harness.agent.deliverable;
