# ourTravel「传火邀请制」部署与验收（ALInvite 2.1.0）

## 1. 目标环境

- 目标服务端：Lophine 26.2 / Folia 调度，Java 25。
- ALInvite 构建目标：Java 21，`plugin.yml` 保持 `api-version: '1.20'`、`folia-supported: true`。
- 当前 `test-server` 已验证内核版本，但现有插件只有 ExcellentShop、nightcore、VaultUnlocked、FAWE。

## 2. 必须先安装的依赖

| 依赖 | 用途 | 缺失时的影响 |
|---|---|---|
| LuckPerms | veteran 权限、权限组奖励 | `lp` 授权命令无法完成自动解锁，权限组奖励不可用 |
| PlaceholderAPI | 外部在线时长变量、菜单占位符 | ALInvite 会回退内置 `PLAY_ONE_MINUTE`，外部占位符不可用 |
| PlayerPoints | 新人/里程碑点券奖励 | 点券奖励和购买扣点会记录 warning，但无法发放 |
| VaultUnlocked + Economy provider | 金币奖励与礼包金币价格 | VaultUnlocked 只是接口层；没有 Economy provider 时金币不可用 |
| 在线时长插件（可选） | 提供 PAPI 时长变量 | 不装也可使用 Bukkit 内置统计回退 |
| Redis（跨服可选） | 公告同步、缓存失效 | 单服不受影响；跨服需 Redis + MySQL |

依赖均应放入服务器 `plugins/`，启动一次确认没有加载错误后再安装 ALInvite。

## 3. 配置要点

默认 `config.yml` 已按传火规则设置：

- 6 位邀请码，`zh_cn`；
- 每人初始 1 个邀请名额，用尽后每 24 小时恢复 1 个，最多积累 1 个；
- IP 限制默认开启；
- 新人礼包：面包 32、石镐 1、火把 16、PlayerPoints 20；
- 里程碑：1/3/10 人分别 10/50/200 点券，手动领取；
- 在线累计 24 小时自动获得 `alinvite.veteran`；PAPI 未安装/未解析时回退 `PLAY_ONE_MINUTE`；
- 绑定成功执行 `whitelist add {player}`（可关闭或替换命令）；
- 连带处罚默认开启，但只由管理员手动执行。

## 4. 原生白名单的关键限制

当 `server.properties` 设置 `white-list=true` 时，服务端会在玩家进入服务器之前拦截不在 `whitelist.json` 的玩家。ALInvite 无法在玩家尚未进入服务器时读取其邀请码并完成绑定，因此：

1. **不能**把“绑定成功后执行 `whitelist add`”理解为“新人凭邀请码首次登录”；
2. 首次登录必须使用代理层门禁/激活码、临时关闭原生白名单，或预先把测试账号加入白名单；
3. 绑定成功后的白名单登记只保证后续连接可进入；
4. offline-mode 环境下应由代理层负责身份与 UUID 策略，不能把离线用户名当作安全身份验证。

## 5. 端到端验收矩阵

| 场景 | 操作 | 预期 |
|---|---|---|
| veteran 生成邀请码 | `/alinvite code` 或 `/alinvite generate` | 返回 6 位邀请码 |
| 首次绑定 | 测试新人执行 `/alinvite bind <code>` | 事务写入邀请关系、消耗 1 名额、登记白名单、发新人礼包 |
| 重复绑定 | 同一新人再次绑定 | 拒绝，不重复计数/发奖 |
| 名额耗尽 | 同一 veteran 第二次绑定 | 提示等待 24 小时，不增加邀请统计 |
| 名额恢复 | 调整测试库时间或等待 24 小时后再次绑定 | 恢复 1 个名额，仍不超过上限 |
| IP 防刷 | 同 IP 测试账号绑定 | 按 IP 规则拒绝或限制功能 |
| 里程碑 | 邀请达到 1/3/10 人，打开 veteran 菜单领取 | 只可领取一次，点券通过 PlayerPoints 发放 |
| 自动 veteran | 达到 24 小时（测试可改为秒/分钟） | 获得 `alinvite.veteran`，自动生成个人邀请码 |
| 连带处罚 | `/alinvite admin punish <被邀请人> 违规原因` | 保留关系，处罚只执行一次，扣邀请人统计/当前可用名额 |
| 跨服 | MySQL + Redis，两个服务服同时绑定/领取 | 唯一索引与事务防重复，公告/缓存失效同步 |

## 6. 当前无法在 test-server 完成的项目

在依赖安装前，以下项目只能验证降级日志，不能判定完整功能通过：

- LuckPerms 权限组与自动授予；
- PlaceholderAPI 外部时长变量；
- PlayerPoints 点券发放；
- Vault Economy 金币发放；
- Redis 跨服广播；
- 原生白名单门禁下的“首次登录后绑定”流程。

构建验证命令（需要 Java 21+ 与 Maven 3.9+）：

```powershell
mvn clean test package
```

产物为 `target/ALInvite-2.1.0.jar`；部署 shaded JAR，不部署 `original-ALInvite-2.1.0.jar`。
