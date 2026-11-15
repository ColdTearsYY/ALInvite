# 宽限期在线邀请验证记录

2026-11-15 完成本地实现与隔离验证。新人仍在绑定宽限期时，邀请人使用 `/alinvite add <新人名>`，可自动完成邀请并解除门禁；同服即时触发认领，共享 MySQL 的跨服场景由新人所在后端定时检查。

## 交付物

- JAR：`target/ALInvite-2.2.0.jar`，20,816,685 字节。
- SHA256：`FAFDE55FEE491492776D228862DAB177BB92E409DC28A87F5F094F561D1D2CFC`。
- 校验文件：`target/ALInvite-2.2.0.jar.sha256`。
- 使用说明：[宽限期在线邀请](live-add.md)。
- 本次总验证记录：工作区 `Temp/tests/alinvite-live-add/final-validation.json`。

本文记录发布前完成的隔离验收；正式产物见 [v2.2.0 Release](https://github.com/ColdTearsYY/ALInvite/releases/tag/v2.2.0)。扩展前已验收的稳定性 JAR 保存在工作区 `Temp/tests/alinvite-live-add/baseline/`，其哈希为 `D4FEA6DD034AB24BEFE7B516A97E1CE2A8ABC629DB5989023276FDD34E96C048`；此前稳定性报告仍对应该基线。

## 构建与事务验证

| 检查 | 结果 | 证据（工作区相对路径） |
| --- | --- | --- |
| Java 21 Maven 离线完整构建 | 135 项测试，失败/错误/跳过均为 0 | `Temp/tests/alinvite-live-add/build-summary.json`、`full-tests/` |
| 真实 MySQL 8.0.12 事务测试 | 18 项通过，失败/错误/跳过均为 0 | `Temp/tests/alinvite-live-add/mysql-tests/` |
| 原有真实客户端流程回归 | 23 项检查通过 | `Temp/tests/alinvite-live-add/regression/liveadd-regression-a/result.json` |
| 在线 `/add` 真实客户端验证 | 8 组场景、43 项检查全部通过 | `Temp/tests/alinvite-live-add/runtime/runs/liveadd-d/result.json` |
| 包内容与源码一致性 | 新类与两种语言文本已入包，未夹带探针；源码哈希匹配 | `Temp/tests/alinvite-live-add/artifact-check.json`、`source-manifest.json` |
| 差异格式与清理 | `git diff --check` 通过；测试进程正常退出、端口释放 | `Temp/tests/alinvite-live-add/diff-check.log`、`cleanup.json` |

本轮新增 8 项纯会话状态测试和 4 项真实事务测试。覆盖初始化顺序、原截止时间、仅查询不得延长宽限期、截止前已受理事务允许完成、断线关闭、旧回调隔离、重复触发、拒绝认领时保留预留码、多个邀请人竞争，以及自动认领与手动绑定竞争。18 项事务测试分别在 SQLite 和真实 MySQL 上执行；这些是不同环境下的同一套事务断言，不应当作 36 个不同用例。

## 在线邀请运行结果

测试使用两台实际 Lophine/Folia 26.3 后端，共享同一个隔离 MySQL 数据库及独立表前缀。邀请命令由已连接的网络客户端发送；探针只准备测试资格、读取状态和导出证据。

| 场景 | 观察结果 |
| --- | --- |
| 同服在线新人 | 先观察到 SPECTATOR 和门禁锁定，再由真实邀请人发送 `/add`；约 817 ms 后关系成立并恢复 SURVIVAL，新人未输入绑定命令 |
| 同服重复邀请与重连 | 重复 `/add` 被拒绝；重连保留 UUID 与关系，礼包数量和任务数不增加 |
| A 服邀请人、B 服新人 | A 服真实客户端发送 `/add` 后，B 服约 1,053 ms 完成放行；新人未发送命令 |
| 两个实际邀请人竞争 | A/B 两名邀请人向同一新人发出 `/add`，最终只建立一条关系、使用一个码 |
| 手动 `/bind` 与 `/add` 竞争 | 两个真实客户端同时发出命令，仅一条关系、一个码消耗，礼包一份 |
| 断线后邀请与重连 | 新人离线后预留原码；存储期限恰为 72,000,000 ms，重连认领并删除预邀请 |
| 宽限期结束 | 隔离配置设为 20 秒；未受邀新人约 20,142 ms 被踢出，没有关系或预邀请 |
| 不符合条件的操作 | 已准入的在线目标被拒绝，邀请人原码保留；门禁中的邀请人无法邀请别人 |

最终数据库中，9 个初始测试邀请码为 **5 个 USED、4 个 AVAILABLE、0 个 RESERVED**，没有待认领记录。五名受邀者各有一条关系；25 条白名单/礼包任务均为 DONE。每名新人收到面包 32、石镐 1、火把 16；测试点券服务各记入 20 点，同服重连没有重复发放。

这些延迟是该次隔离运行的观测值，不是生产延迟保证。生产宽限期默认仍为 60 秒，测试缩短到 20 秒只为验证超时。

## 验证范围与失败记录

网络客户端使用 `minecraft-protocol` 26.1，经已校验来源与 SHA-512 的 ViaVersion/ViaBackwards 转接到 Folia 26.3。测试确实触发 Bukkit 登录/退出、玩家命令、库存和门禁状态；它不是原生图形 26.3 客户端验证。

测试使用离线 loopback 身份，邀请人的既有资格和累计 48 小时库存由明确标识的夹具准备。点券为隔离反射接口夹具，未声称验证正式 PlayerPoints/Vault。为使同 IP 的本机客户端可检查完整礼包，隔离配置显式启用 `ip_restriction.flexible_mode.gift/milestone`；生产默认 IP 规则没有改变。Velocity/MultiLogin、正式账号及完整生产插件组合仍未验证。

保留了全部非通过运行，未将它们计为成功：

- `liveadd-a`：测试客户端替换 JSON 证据文件遇到 Windows EPERM，客户端提前退出。改为直接写入并由读取侧重试。
- `liveadd-b`：绑定成功，但同 IP 的既有奖励限制关闭了礼包。调整隔离测试配置以覆盖完整发放；原失败同时证明该限制仍生效。
- `liveadd-c`：绑定和礼包成功，PowerShell 默认 JSON 解析器拒绝 Minecraft 数据中的空字段名。读取改为 `-AsHashtable`。
- `liveadd-d`：全套 8 组/43 项通过，使用同一生产 JAR。

测试脚本子任务第一次未交付文件，第二次超时留下草稿，由主任务接手修正并实际执行；没有把子任务结束状态或未交付报告视为验收通过。相关记录在 `harness-error.json` 和 `harness-takeover.json`。

两台在线邀请测试服退出码均为 0；回归测试服同样正常退出。核实进程身份后关闭了本轮隔离 MySQL（PID 20044、端口 33317），最终 33317、35691、35692、35701、35702 均无监听。未操作生产服务。
