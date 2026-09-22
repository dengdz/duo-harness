---
name: duo-java-review
description: Java 代码规范审查技能，基于阿里巴巴 Java 开发手册（嵩山版）逐条对照审查，输出 BLOCKER/CRITICAL/MAJOR 分级发现。当需要按阿里规范审查 Java 代码、检查命名/OOP/集合/并发/异常/日志等规约、或作为 duo-code-review 的 Java 规范轴时使用。
---

# Java代码审查技能 - 阿里巴巴Java开发手册

## 概述

本技能基于《阿里巴巴Java开发手册》（嵩山版）对Java代码进行全面审查。审查时需逐条对照规范，发现违规项后给出明确的违规等级、违规位置和修复建议。

本文件（SKILL.md）是技能的入口和索引，仅包含工作流和文件加载策略。**完整的规则细则、示例和模板按需从附属文件加载**，避免一次性占用过多上下文。

## 违规等级定义

| 等级 | 说明 |
|------|------|
| **BLOCKER（强制）** | 必须遵守，违反可能导致Bug、安全漏洞或性能问题 |
| **CRITICAL（推荐）** | 强烈建议遵守，违反可能导致潜在问题 |
| **MAJOR（参考）** | 建议遵守，提升代码可读性和可维护性 |

## 渐进式文件加载策略

本技能采用三层渐进式披露，按需加载文件以节省上下文窗口。

### L0 - 始终加载（本文件）
当前你正在阅读的文件。包含工作流、文件索引和加载决策逻辑。

### L1 - 审查时按模块加载（规则细则）
审查代码时，根据代码内容判断涉及哪些模块，**仅加载相关的规则文件**：

| 文件 | 内容 | 规则编号范围 | 加载时机 |
|------|------|-------------|----------|
| `reference/rules-programming.md` | 编程规约：命名、常量、格式、OOP、集合、并发、控制语句、注释 | N/C/F/O/COL/CON/CTRL/COM | 代码包含类定义、方法、集合操作、线程/锁、流程控制时 |
| `reference/rules-exception-log.md` | 异常处理、日志规约 | E/L | 代码包含try-catch、异常抛出、日志输出时 |
| `reference/rules-test-security.md` | 单元测试、安全规约 | UT/S | 代码是测试类，或涉及权限校验、敏感数据、SQL拼接、密码处理时 |
| `reference/rules-mysql-project.md` | MySQL数据库、工程结构、服务器端规范 | DB/PRJ/SRV | 代码涉及SQL语句、MyBatis映射、分层架构、API接口时 |

**加载决策逻辑：**
1. 先通读待审查代码，识别涉及的模块（如：是否有集合操作？是否有并发？是否有SQL？）
2. 仅加载命中的规则文件。例如纯业务Service类通常只需加载 `reference/rules-programming.md` + `reference/rules-exception-log.md`
3. 如果代码覆盖面广（如一个完整的Controller+Service+DAO项目），可加载全部L1文件

### L2 - 输出时按需加载（辅助资源）
在审查流程的不同阶段，按需加载以下辅助文件：

| 文件 | 用途 | 加载时机 |
|------|------|----------|
| `reference/checklist.md` | 快速检查清单，按模块列出BLOCKER级要点 | 用户要求"快速检查"或"快速review"时；或审查开始前作为速查参考 |
| `reference/examples.md` | 常见违规代码示例与修复方案（40+个代码示例） | 需要为违规项提供修复示例代码时加载，避免每条违规都从零编写示例 |
| `reference/report-template.md` | 审查报告输出模板 | 进入"输出审查报告"阶段时加载，按模板格式输出最终报告 |

## 审查工作流

### 第一步：理解代码上下文
1. 阅读待审查代码文件及其依赖文件，理解代码的业务意图和技术架构。
2. 确认使用的Java版本、框架版本（如Spring Boot）和构建工具（Maven/Gradle）。
3. 识别代码类型：业务代码、工具类、配置类、测试代码等。

### 第二步：识别涉及模块，加载L1规则文件
1. 通读代码，识别涉及的规范模块（命名/常量/格式/OOP/集合/并发/控制语句/注释/异常/日志/测试/安全/MySQL/工程结构）。
2. 按照"渐进式文件加载策略"中的L1表格，**仅加载相关的规则文件**。
   - 若用户要求快速检查，可先加载 `reference/checklist.md` 作为速查参考。
   - 典型场景举例：
     - **纯工具类**：通常只需 `reference/rules-programming.md`
     - **Service业务类**：`reference/rules-programming.md` + `reference/rules-exception-log.md`
     - **Controller类**：`reference/rules-programming.md` + `reference/rules-exception-log.md` + `reference/rules-mysql-project.md`
     - **测试类**：`reference/rules-test-security.md` + `reference/rules-programming.md`
     - **DAO/Mapper类**：`reference/rules-mysql-project.md` + `reference/rules-programming.md`
     - **完整项目（多文件）**：加载全部4个L1规则文件

### 第三步：逐模块审查
按照以下顺序逐模块审查，每个模块对照已加载规则文件中相应的条目：

1. 编程规约（命名、常量、格式、OOP、集合、并发、控制语句、注释）
2. 异常日志（异常处理、日志规约）
3. 单元测试
4. 安全规约
5. MySQL数据库（如涉及）
6. 工程结构（如涉及）

每发现一条违规，记录：文件路径+行号、违规等级、规则编号、问题描述。

### 第四步：加载L2辅助文件，生成审查报告
1. 加载 `reference/report-template.md` 获取报告输出格式。
2. 若需要为违规项提供修复示例，加载 `reference/examples.md` 查找对应的违规示例代码。
3. 按模板格式输出审查报告，包含：
   - 审查概要（文件列表、违规统计）
   - 详细违规项（文件路径+行号、违规等级、规则编号、问题描述、修复建议+示例代码）
   - 改进建议汇总表

## 规范模块速查索引

以下为所有规则模块的编号范围速查表，用于快速定位规则编号对应的文件：

| 规则编号前缀 | 所属模块 | 对应文件 |
|-------------|---------|---------|
| N- | 命名规约 | `reference/rules-programming.md` |
| C- | 常量定义 | `reference/rules-programming.md` |
| F- | 代码格式 | `reference/rules-programming.md` |
| O- | OOP规约 | `reference/rules-programming.md` |
| COL- | 集合处理 | `reference/rules-programming.md` |
| CON- | 并发处理 | `reference/rules-programming.md` |
| CTRL- | 控制语句 | `reference/rules-programming.md` |
| COM- | 注释规约 | `reference/rules-programming.md` |
| E- | 异常处理 | `reference/rules-exception-log.md` |
| L- | 日志规约 | `reference/rules-exception-log.md` |
| UT- | 单元测试 | `reference/rules-test-security.md` |
| S- | 安全规约 | `reference/rules-test-security.md` |
| DB- | MySQL数据库 | `reference/rules-mysql-project.md` |
| PRJ- | 工程结构 | `reference/rules-mysql-project.md` |
| SRV- | 服务器端规范 | `reference/rules-mysql-project.md` |

## 审查报告基本格式

> 完整模板见 `reference/report-template.md`，以下为简要结构：

```
## Java代码审查报告

### 审查概要
- 审查文件：[文件列表]
- 违规统计：BLOCKER X项，CRITICAL Y项，MAJOR Z项

### 详细违规项
#### 1. [BLOCKER] [规则编号] - [规则名称]
- 文件：src/main/java/com/example/XxxClass.java:行号
- 规范：[规则描述]
- 问题：[具体问题描述]
- 建议：[修复建议，附示例代码]

### 改进建议汇总
| 优先级 | 改进项 | 涉及文件 |
```
