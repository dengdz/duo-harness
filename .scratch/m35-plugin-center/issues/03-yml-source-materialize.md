# 03: yml 可写事实源——fat-jar 资源首启物化

## What to build

`java -jar` 形态（启动来源是 classpath 资源、物理不可写）下，首次启动把资源 yml 物化到 DUO_HOME，此后进程装载只读物化文件——保住"插件中心操作写回单一事实源"裁定不被架空（spec Implementation Decisions 在案）。用户显式给 yml 文件路径启动时行为不变。

验收标准：资源形态首启产出物化文件且装载切换到物化文件；文件形态启动与既有 `Boot.fromResource` 用例零行为变化。

## Status

ready-for-agent

## Checklist

- [ ] 来源解析：文件路径优先 → 缺失时资源种子物化 → 物化文件成为此后唯一装载来源（资源仅首启种子）
- [ ] 物化原子性：写盘失败 fail-fast 点名（不半写）
- [ ] 升级漂移检测：物化后内置种子演进时启动日志提示对账（合并工具不做，如实记档）
- [ ] 兼容回归：既有文件形态启动、资源形态启动用例全绿
- [ ] CHANGELOG 记账（用户可见：fat-jar 首启物化行为，同 diff）
