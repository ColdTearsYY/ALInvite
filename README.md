# 🎯 ALInvite - 我的世界服务器邀请系统插件

## 📖 概述

**ALInvite** 是一个功能强大的我的世界服务器邀请系统插件，旨在通过激励玩家邀请新玩家加入服务器，促进服务器人口增长和社区活跃度。插件支持多语言、自定义菜单、礼包商店、里程碑奖励等丰富功能，完全兼容 Folia 多线程服务器。

### ✨ 主要特性

| 功能模块                  | 描述                        |
| --------------------- | ------------------------- |
| 🔑 **智能邀请码系统**        | 每个老玩家拥有独立的邀请码，支持自定义生成规则   |
| 🎁 **礼包商店系统**         | 老玩家可购买不同等级的礼包，新玩家获得对应奖励   |
| 🏆 **里程碑系统**          | 累计邀请达到指定人数时发放额外奖励，支持全服公告  |
| 💰 **点券充值返点**         | 支持点券充值返点功能，自动计算返点比例       |
| 💵 **贡献返点系统**         | 支持贡献返点记录和兑换功能             |
| 📊 **排行榜系统**          | 提供邀请人数、贡献返点、点券返点三种排行榜     |
| 🛡️ **智能IP限制**        | 灵活的IP限制机制，防止刷小号和作弊行为      |
| 🌐 **多语言支持**          | 内置中文和英文语言包，支持国际化          |
| 🖥️ **GUI菜单系统**       | 直观易用的图形界面，支持动态按钮和状态显示     |
| ⚡ **权限组奖励**           | 与LuckPerms等权限插件集成，支持权限组奖励 |
| 🔌 **PlaceholderAPI** | 支持多种占位符，方便其他插件集成          |
| ⚡ **Folia兼容性**        | 完全支持Folia多线程服务器，性能优化      |
| 🚀 **性能优化**           | 数据库索引、缓存、异步处理优化           |

## 🔄 v2.1.0 传火/Folia 26.2 升级说明（2026-09）

本版本在 v2.0.0 的 Folia 调度与数据层重构基础上，按 ourTravel「传火邀请制」实施：

- **兼容目标**：保持 `api-version: '1.20'` 与 `folia-supported: true`，使用 Folia Global/Entity/Async 调度器；已在 Java 21 下构建，可运行于 test-server 的 Lophine 26.2 / Java 25。
- **传火配额**：每名传火者默认 1 个邀请名额，用完后每 24 小时恢复 1 个；名额刷新、邀请记录、邀请总数在 SQLite/MySQL 事务中一起提交，避免并发/跨服超发。
- **奖励默认值**：新人礼包为面包 32、石镐 1、火把 16、PlayerPoints 20；邀请 1/3/10 人里程碑分别为 10/50/200 点券，默认手动领取。
- **24 小时解锁**：优先使用 PlaceholderAPI 在线时长占位符；PAPI 不存在或占位符未解析时回退 Bukkit `PLAY_ONE_MINUTE` 统计。
- **处罚审计**：`/alinvite admin punish <被邀请人> [原因]` 由管理员确认违规后手动执行；记录不会删除，重复执行不会重复扣减。插件不自动监听封禁。
- **白名单边界**：绑定成功后可执行配置的 `whitelist add {player}`。但原生 `white-list=true` 会在登录前拦截不在白名单的新人，插件无法让新人仅凭邀请码首次登录；请使用代理门禁、临时白名单或预先导入账号，绑定后的登记只对后续连接生效。

## 🔄 v2.0.0 重构说明（2026-09）

本版本对调度器、GUI、数据层做了全面重构（架构方案见 [REFACTOR_PLAN.md](REFACTOR_PLAN.md)）：

- **统一调度器**：全插件唯一的 `ALInviteScheduler`（自动探测 Folia/Paper/Spigot，任务句柄登记 + `cancelAll`），
  禁止业务代码裸调 Bukkit 调度 API；新增命名 IO 线程池，数据库查询不再依赖 ForkJoinPool.commonPool。
- **菜单架构**：`menus.yml` 单文件迁移为 `menus/` 目录（一菜一文件，`menus_en/` 英文对应），
  布局/文案/动作全部 YAML 化（`triggers.left/right/shift_*`、`delay:`/`sound:` DSL、动态槽位 + 状态样式），
  动作编码进物品 PDC，点击经注册表分发；旧版配置整体备份后重新生成默认配置（见下方升级说明）。
- **性能修复**：菜单渲染改为"异步取数快照 → 实体线程填充"，修复旧版每次打开菜单在主线程产生上千次数据库查询的问题；
  PAPI 占位符接入缓存；奖励发放（物品/经济/命令）统一切回玩家实体线程。
- **修复的 bug**：礼包商店第二行点错礼包、里程碑页码上限错误、关闭菜单后界面被异步数据幽灵弹回、
  Paper 1.21+ 拖拽保护失效、reload 后旧定时任务泄漏、聊天输入无真实超时等。
- **兼容性**：指令、权限、占位符、API 全部不变。
- **升级策略（重要）**：1.2.x 及更早版本的配置结构已不兼容。首次运行 2.0.0+ 时，插件会将旧配置（config.yml、menus.yml、menus/、languages/ 等）**整体备份到 `plugins/ALInvite/backup/` 目录**，然后重新生成全套默认配置，服主按新格式重新调整即可。此后（version >= 3）的正常版本更新只做"只补缺失"合并升级，**不会**再清空或重生成配置，服主的修改持续保留。
- **充值返利中心（主菜单 S 按钮）**：返利不再自动入账，进入"未领取池"；S 按钮显示累计返点与未领取数量，
  左键手动领取（计入贡献返点余额），右键打开返利记录菜单（记录每笔返利的时间、金额与来源玩家）。
- **导航统一**：除主菜单保持 3 行外，其余菜单为 6 行布局，统一提供「返回（按固定层级回到上级：主菜单 → 邀请中心 → 礼包商店）」「返回主菜单」「关闭」按钮；全部菜单 lore 采用 ALFriends 风格（▶ 分节标题、▸ 信息行、• 操作提示）。
- **自定义物品与空白槽位**：菜单物品 material 支持 `ce:`（CraftEngine）、`ia:`（ItemsAdder）、`oraxen:` 前缀（反射解析、带缓存、未安装插件自动回退），支持 `AIR` 留空槽位。
- **服务器标识与时区**：config.yml 新增 `serverid` / `serverName` / `time.timezone`，跨服公告带别称前缀，返利记录时间按配置时区显示。
- **database.yml 独立**：数据库与 Redis 跨服配置拆分到 `database.yml`，随旧配置一起备份重生成。
- **Redis 跨服同步**：里程碑公告跨服广播、玩家数据缓存跨服失效广播；Jedis 连接池 + 断线自动重连，未启用时零开销。
- **跨服防二次领取/发放**：里程碑领取改为数据库原子抢占（条件 UPDATE，只有一方成功）；邀请记录 invitee_uuid 唯一索引防重复计数；充值返点流水占用（transaction_key 唯一键 + 状态标记），重复事件只发放一次。

## 🚀 安装指南

### 📋 前置要求

| 项目                  | 要求                           |
| ------------------- | ---------------------------- |
| **Minecraft 服务器版本** | 1.20.1+（目标：Lophine/Folia 26.2） |
| **Java 版本**         | **21+（Lophine 26.2 测试服使用 Java 25）** |
| **服务器类型**           | 支持 Folia（推荐）或 Paper          |
| **可选依赖**            | PlaceholderAPI、LuckPerms（可选） |

### 📦 安装步骤

1. 📥 将 `ALInvite-shaded.jar` 文件放入服务器的 `plugins` 文件夹
2. 🔄 重启服务器
3. ⚙️ 插件会自动生成配置文件
4. ✏️ 根据需要修改配置文件
5. 🔄 重新加载配置或重启服务器

### ⚡ 服务器类型支持

| 服务器类型       | 支持状态 | 性能表现    |
| ----------- | ---- | ------- |
| ✅ **Folia** | 完全支持 | 🚀 最佳性能 |
| ✅ **Paper** | 完全支持 | ⚡ 优秀性能  |
| ⚠️ **其他**   | 可能支持 | 性能一般    |

## ⚙️ 配置说明

### 🔧 主配置文件 (config.yml)

#### 语言设置

```yaml
language:
  locale: "zh_cn"  # 语言文件标识：zh_cn（中文）、en_us（英文）
```

#### 邀请码设置

```yaml
invite_code:
  length: 6                     # 邀请码长度（4-8位）
  charset: "ABCDEFGHJKLMNPQRSTUVWXYZ0123456789"  # 字符集（排除易混淆字符）
  prefix: ""                    # 邀请码前缀
  veteran_permission: "alinvite.veteran"  # 老玩家权限
  use_permission: "alinvite.use"          # 使用权限
  allow_veteran_to_bind: false   # 是否允许老玩家绑定老玩家
```

#### IP限制设置

```yaml
ip_restriction:
  enabled: false                 # 是否启用同IP绑定限制
  flexible_mode:
    milestone: false             # 同IP绑定：里程碑功能禁用
    rebate: true                 # 同IP绑定：返点功能启用
    gift: false                  # 同IP绑定：礼包功能禁用
```

#### 里程碑系统

```yaml
milestones:
  auto_claim: false               # 是否自动领取里程碑奖励
  1:
    name: "社交新星"             # 里程碑名称
    lore:                        # 奖励描述
      - "&a100 金币"
      - "&b10 点券"
    rewards:                     # 奖励列表
      - type: "money"            # 奖励类型：money/points/command/item
        value: 100
      - type: "points"
        value: 10
```

#### 礼包商店

```yaml
gift_shop:
  enabled: true                 # 是否启用礼包购买功能
  gifts:
    default:                    # 默认礼包
      name: "&6基础礼包"
      material: "GOLD_INGOT"
      price_money: 0            # 金币价格
      price_points: 0           # 点券价格
      duration_days: 0          # 购买时限：天数（0 表示永久）
      rewards:                  # 新玩家奖励
        - type: "money"
          value: 50
        - type: "item"
          value: "COOKED_BEEF 64"
```

#### 全服公告

```yaml
announcements:
  enabled: true                 # 是否启用里程碑公告
  mode: "BROADCAST"             # 公告方式：BROADCAST/WORLD/CONSOLE
  cross_server_sync: true       # 是否启用跨服同步
  messages:
    default: "&6[邀请系统] &e{player} &a累计邀请人数达到 &6{total} &a人！"
    1: "&a⭐ {player} 成为了社交新星，邀请 1 人！"
```

### 🖥️ 菜单配置 (menus.yml)

#### 主菜单配置示例

```yaml
main_menu:
  title: "&6&l邀请系统"
  shape:
    - "#########"
    - "#A#####B#"
    - "#########"
  buttons:
    A:
      material: "WRITABLE_BOOK"
      name: "&a&l填写邀请码"
      lore:
        - "&7绑定状态: &e{bind_status}"
        - "&7邀请人: &e{inviter_name}"
      action: "INPUT_CODE"
```

#### 动态按钮支持

- `{invite_code}` - 玩家邀请码
- `{bind_status}` - 绑定状态
- `{inviter_name}` - 邀请人名称
- `{total_rebate}` - 累计返点
- `{milestone_name}` - 里程碑名称
- `{current}`/`{required}` - 里程碑进度

## 🎮 使用方法

### 👤 玩家使用指南

#### 新玩家操作

1. 输入 `/alinvite` 打开主菜单
2. 点击"填写邀请码"按钮
3. 输入老玩家的邀请码
4. 获得新手礼包奖励

#### 老玩家操作

1. 输入 `/alinvite` 打开主菜单
2. 点击"邀请中心"查看统计
3. 在礼包商店购买礼包
4. 领取里程碑奖励
5. 分享邀请码给新玩家

### 🔧 命令列表

#### 玩家命令

| 命令                  | 权限                 | 描述       |
| ------------------- | ------------------ | -------- |
| `/alinvite`         | `alinvite.use`     | 打开主菜单    |
| `/alinvite bind <邀请码>`    | `alinvite.use`     | 直接绑定邀请码    |
| `/alinvite code`    | `alinvite.use`     | 查看自己的邀请码 |
| `/alinvite stats`   | `alinvite.use`     | 查看邀请统计信息 |
| `/alinvite buygift` | `alinvite.buygift` | 打开礼包商店   |
| `/alinvite help`    | `alinvite.use`     | 查看帮助信息   |

#### 管理员命令

| 命令                                    | 权限               | 描述           |
| ------------------------------------- | ---------------- | ------------ |
| `/alinvite admin help`                | `alinvite.admin` | 显示管理帮助信息     |
| `/alinvite admin reload`              | `alinvite.admin` | 重载插件配置       |
| `/alinvite admin givecode <玩家>`       | `alinvite.admin` | 为玩家生成邀请码     |
| `/alinvite admin clearcode <玩家>`      | `alinvite.admin` | 清除玩家邀请码      |
| `/alinvite admin addinvite <玩家> <数量>` | `alinvite.admin` | 增加邀请次数       |
| `/alinvite admin reset <玩家>`          | `alinvite.admin` | 重置玩家邀请数据     |
| `/alinvite admin punish <被邀请人> [原因]` | `alinvite.admin` | 确认违规后执行连带处罚（不自动监听封禁） |
| `/alinvite admin announce`            | `alinvite.admin` | 发送全服公告       |
| `/alinvite givedj <玩家> <金额>`          | `alinvite.admin` | 给玩家充值点券并处理返点 |

#### 贡献返点管理命令

| 命令                                         | 权限               | 描述        |
| ------------------------------------------ | ---------------- | --------- |
| `/alinvite admin contrib <玩家>`             | `alinvite.admin` | 查看玩家贡献返点  |
| `/alinvite admin contrib add <玩家> <金额>`    | `alinvite.admin` | 增加贡献返点    |
| `/alinvite admin contrib set <玩家> <金额>`    | `alinvite.admin` | 设置贡献返点    |
| `/alinvite admin contrib deduct <玩家> <金额>` | `alinvite.admin` | 扣除贡献返点    |
| `/alinvite admin contrib clear <玩家>`       | `alinvite.admin` | 清空贡献返点    |
| `/alinvite admin contrib h <玩家> <金额>`      | `alinvite.admin` | 兑换贡献返点为点券 |

## 🔌 第三方集成

### 📊 PlaceholderAPI 支持

#### 基础信息占位符

| 占位符                              | 描述             | 示例                |
| -------------------------------- | -------------- | ----------------- |
| `%alinvite_code%`                | 玩家邀请码          | `ABC123`          |
| `%alinvite_total%`               | 累计邀请人数         | `5`               |
| `%alinvite_total_invites%`       | 累计邀请人数（同total） | `5`               |
| `%alinvite_gift_name%`           | 当前礼包名称         | `基础礼包`            |
| `%alinvite_has_gift%`            | 是否拥有礼包         | `true`/`false`    |
| `%alinvite_gift_status%`         | 礼包状态           | `已购买`/`未购买`/`已过期` |
| `%alinvite_gift_remaining_days%` | 礼包剩余天数         | `30天`/`永久`/`未购买`  |
| `%alinvite_bind_status%`         | 绑定状态           | `已绑定`/`未绑定`       |
| `%alinvite_inviter_name%`        | 邀请人名称          | `PlayerName`      |

#### 里程碑相关占位符

| 占位符                                       | 描述           | 示例             |
| ----------------------------------------- | ------------ | -------------- |
| `%alinvite_next_milestone%`               | 下一个里程碑人数     | `10`           |
| `%alinvite_next_milestone_name%`          | 下一个里程碑名称     | `社交达人`         |
| `%alinvite_remaining_for_next_milestone%` | 距离下一个里程碑还需人数 | `3`            |
| `%alinvite_milestone_5%`                  | 是否达到指定里程碑    | `true`/`false` |

#### 排行榜相关占位符

**已实现功能**

| 占位符                                    | 描述        | 示例             |
| -------------------------------------- | --------- | -------------- |
| `%alinvite_rank_invite%`               | 玩家在邀请榜的排名 | `1`            |
| `%alinvite_rank_contribution%`         | 玩家在贡献榜的排名 | `5`            |
| `%alinvite_rank_points%`               | 玩家在点券榜的排名 | `3`            |
| `%alinvite_my_invites%`                | 玩家本人的邀请人数 | `10`           |
| `%alinvite_my_contribution%`           | 玩家本人的贡献返点 | `500`          |
| `%alinvite_my_points%`                 | 玩家本人的点券返点 | `250`          |
| `%alinvite_top_invite_1_player%`       | 邀请榜第一名玩家名 | `TopPlayer`    |
| `%alinvite_top_invite_1_count%`        | 邀请榜第一名邀请数 | `50`           |
| `%alinvite_top_contribution_1_player%` | 贡献榜第一名玩家名 | `RichPlayer`   |
| `%alinvite_top_contribution_1_rebate%` | 贡献榜第一名返点数 | `1000`         |
| `%alinvite_top_points_1_player%`       | 点券榜第一名玩家名 | `PointsPlayer` |
| `%alinvite_top_points_1_rebate%`       | 点券榜第一名返点数 | `500`          |

**占位符格式说明：**

- **玩家排名**: `%alinvite_rank_类型%`（类型：invite/contribution/points）
- **玩家数值**: `%alinvite_my_类型%`（类型：invites/contribution/points）
- **排行榜前10**: `%alinvite_top_类型_排名_字段%`
  - 类型：invite/contribution/points
  - 排名：1-10
  - 字段：player（玩家名）/count（邀请数）/rebate（返点数）

**功能特性：**

- ✅ 支持玩家本人排名显示
- ✅ 支持玩家本人数值显示
- ✅ 支持前10名玩家和数值显示

### 🔗 API 集成

#### 获取插件实例

```java
ALInvite plugin = (ALInvite) Bukkit.getPluginManager().getPlugin("ALInvite");
```

#### 通过命令调用集成

```yaml
# 第三方插件配置示例
commands:
  - '[console]alinvite givedj %player_name% %points%'
```

## 🛠️ 高级功能

### ⚡ 性能优化特性

1. **异步处理** - 所有数据库操作和计算都使用异步处理
2. **缓存机制** - 玩家数据、邀请码、礼包信息等使用缓存
3. **数据库索引** - 关键字段建立索引，提升查询性能
4. **连接池** - 数据库连接池管理，避免频繁连接

### 🔒 安全特性

1. **IP限制** - 防止同IP作弊行为
2. **防重复检查** - 充值返点防重复机制
3. **权限控制** - 完善的权限系统
4. **输入验证** - 所有输入参数都经过验证

### 🌐 跨服支持

1. **公告同步** - 多服务器间里程碑公告同步
2. **数据共享** - 支持MySQL数据库共享数据
3. **服务器标识** - 每个服务器有唯一标识

## 🐛 故障排除

### ❌ 常见问题

#### 插件无法启动

- 检查Java版本是否为21+
- 检查服务器版本是否为1.20.4+
- 查看控制台错误信息

#### 菜单无法打开

- 检查玩家是否拥有 `alinvite.use` 权限
- 检查菜单配置文件是否正确

#### 邀请码无效

- 检查邀请码长度和字符集设置
- 确认老玩家是否拥有 `alinvite.veteran` 权限

### 🔧 调试方法

1. **开启调试模式** - 在配置文件中启用调试日志
2. **检查权限** - 使用 `luckperms` 检查玩家权限
3. **查看日志** - 检查服务器日志文件

## 📈 性能监控

### 📊 监控指标

- **数据库查询时间** - 监控数据库操作性能
- **内存使用** - 监控缓存和对象内存使用
- **线程使用** - 监控异步线程池状态

### 🔍 优化建议

1. **使用MySQL** - 多服务器环境推荐使用MySQL
2. **调整缓存** - 根据服务器规模调整缓存大小
3. **定期清理** - 定期清理过期数据和日志

## 🤝 社区支持

### 📚 相关链接

- **GitHub仓库**: <https://github.com/AllenLinong/ALInvite>
- **MineBBS发布**: <https://www.minebbs.com/resources/alinvite-folia.16120/>
- **问题反馈**: 在GitHub提交Issue

<br />

## 📄 许可证

本项目采用 MIT 许可证，详情请查看 [LICENSE](LICENSE) 文件。

***

**ALInvite** - 让服务器邀请更有趣！ 🎉
