# 02: 事件快照 CoW 零拷贝

## What to build

框架维护者获得读侧零拷贝的事件访问（ADR-0014 决策 1）：Session 内部维护 volatile 不可变快照引用，append 在锁内追加后重建快照（写侧 O(n)），`events()` 直接返回共享引用（读侧 O(1)）。对外语义不变——调用时刻的稳定不可变视图、与追加线程并发隔离、遍历无 CME——只是从"每次新建一份"变"共享同一份"；javadoc 实现语义同步更新。消费方（投影/回放/分页/治理/计划模式）自动受益，零接口改动。

## Blocked by

None（可立即开工）

## Status

done（2026-09-16 用户验收通过——SessionTest 33 例全绿、WebFaceTest 32 例全绿）

## Checklist
- [x] Session 快照 CoW 化：persist 锁内追加后重建不可变列表，events() 返回共享引用
- [x] javadoc 更新：稳定视图/并发隔离语义不变，实现为 CoW 共享（ADR-0014）
- [x] 用例：events() 返回不可变视图（写操作抛 UnsupportedOperationException）、append 后新事件可见、读遍历与并发 append 隔离（既有并发用例不回归）

## 验收指引（用户手跑）

```bash
# Session 模块全量（33 例 = 32 既有 + 1 新增 CoW 契约；含两处并发隔离用例原样绿）
mvn -pl duo-harness-session -am test

# 下游消费方抽查（回放/分页/占用/标题全链路）
mvn -pl duo-harness-web -am test -Dtest=WebFaceTest -Dsurefire.failIfNoSpecifiedTests=false

# 全量
mvn test
```

预期：全绿。关键断言：新用例 `eventsReturnsSharedImmutableSnapshot`——无追加期间两次 `events()` 返回同一引用（assertSame）、快照不可变（add 抛 UnsupportedOperationException）、追加后快照重建且新事件可见。
