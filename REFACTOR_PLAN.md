# ALInvite GUI 重构方案

> **2026-09-10 增补**：应"不要有性能问题"的要求，对 GUI 之外的数据层/奖励层/占位符层做了全面审查，
> 新增 **第十章：性能与数据层重构清单**（含头号性能问题：菜单渲染的数据库查询风暴），
> 并将原六阶段扩展为八阶段（新增 P7 奖励发放统一、P8 数据层与防重复收口）。

参照 `1.2ALwarp`（shape 菜单 + 统一调度器）与 `ALFriends`（动作管线 + 会话管理 + 分页）的成熟方案，对 ALInvite 的菜单、调度器、lore/文案做一次不改变外在行为的重构。

**本方案只做规划，未改动任何代码。**

---

## 一、现状问题清单（重构动因）

### A. 调度器三套并存，且都是坏的

| 类 | 行数 | 现状 |
|---|---|---|
| `utils/FoliaScheduler.java` | 206 | 全反射实现，**全插件无人调用（纯死代码）** |
| `utils/SchedulerUtils.java` | 202 | 13 个文件在用；但有三个危险实现：① 反射失败时 `new Thread().start()` 兜底（脱离调度体系，停服不回收）；② `runTaskSupplied` 用 `future.join()` 阻塞调用线程；③ `runTaskTimerFolia` 用递归自排实现定时，**句柄不可取消** |
| `utils/ThreadPoolManager.java` | 176 | 初始化了两个线程池并随启随关，**没有任何业务提交过任务（纯死代码）** |

连带问题：
- `onDisable()` 只关数据库和线程池，**没有 `cancelTasks` 兜底**，定时任务停服后可能残留。
- 玩家退出（`PlayerQuitEvent`）**没有人清理** `MenuSession`、`inputStates`（MenuListener 里的聊天输入状态），状态泄漏。
- 聊天输入的"超时"只是记了个时间戳，**没有真的排超时任务**——玩家不说话就永不清理。
- 铁律：全插件只允许存在一个调度器包装类。

### B. GUI 层

1. **`MenuHolder.getInventory()` 返回 `null`**（`MenuHolder.java:19`）。Paper 1.21+ 的拖拽校验要求 Holder 持有真实 Inventory 引用，返回 null 意味着拖拽保护可能失效、物品可被拿走（ALwarp 的 `ShapeMenuHolder` 注释明确写了这个坑）。
2. **异步回填无会话校验**：`MenuManager` 里三层嵌套 `thenAccept`，数据回来后直接 `SchedulerUtils.runTask(plugin, player, () -> player.openInventory(inv))`。玩家中途关掉菜单/打开别的界面后，数据回来仍会把旧菜单**幽灵弹回**。
3. **`MenuSession.getInventory()` 返回的是"玩家当前打开的界面"**，不是会话创建时绑定的那个 Inventory——会话校验是碰巧能用的巧合，不是真正的引用校验。
4. **`MenuManager` 1124 行上帝类**：三个菜单的渲染、lore 拼接、分页全堆在一起；`buildSlotActions` 写了两遍（188 行 / 327 行）；`createInventory()`（304 行）与 `createCurrentGiftPreviewSync()`（976 行，内含 `.join()` 阻塞）是死代码。
5. **点击路由有真 bug**：
   - `handleBuyGift`（`MenuListener.java:366`）用 `giftIndex = slot - 10`、`giftsPerPage = 7` 魔法数字定位礼包，而 shop 布局实际每页 **14 格 G**（两行 ×7）——**第二行的礼包点开会买错**。
   - `validatePage` / `getMaxPage` 用 `milestonesPerPage = 14` 算页数，而 veteran 布局实际每页 **21 格 M**（三行 ×7）——页码上限算错。
6. **翻页整箱重建**：`PREV_PAGE/NEXT_PAGE` 走 `openVeteranMenu/openShopMenu` 全量重开，闪屏且丢进行中状态；用 `shape/shape2` 两套布局表达"第二页"是 hack。
7. **点击无冷却防抖**（两个参考插件都有 `button_cooldown_seconds`）。
8. **文案硬编码在 Java 里**：翻页按钮 lore（"&7当前页: x/y"、"已经是第一页"）、里程碑状态文字（"未解锁/可领取/已领取"）、奖励行（"&a…金币"、"&b…点券"）、"&c邀请码不能为空！"、"&c礼包不存在！" 等，服主改不了、英文服没法用。
9. **`Material.valueOf` 多处无 try-catch**（`createButtonItem/createMainButtonItem/createBackgroundItem`），配置写错材质直接抛异常。
10. `MenuSessionManager` 是单例 + `setPlugin()` 服务定位器反模式；Close 事件里"延迟 1 tick 再查一次"清会话是补丁式写法。

### C. 菜单配置

- 单文件 `menus.yml`（307 行）混三个菜单；`{reward_lore_0}`…`{reward_lore_10}` 要预占 11 行坑位，没填满就显示残留或空行；无 `config_version` 合并升级；无启动校验；无双语目录。
- 代码里逐 key 散读配置（`menus.yml` 的 `main_menu/veteran_menu/shop_menu` 硬编码在 `loadMenus()`）。

---

## 二、目标架构

```
com.alinvite
├── scheduler/
│   └── ALInviteScheduler        # 唯一调度器（取代三个旧类）
├── gui/
│   ├── MenuManager              # 门面：volatile 不可变菜单快照 + open 对外入口
│   ├── MenuConfigLoader         # menus/*.yml 加载 + 校验 + config_version 合并升级
│   ├── MenuConfig / MenuItem    # 配置 POJO（不可变）
│   ├── InviteMenuHolder         # menuName + shape + 真实 Inventory 引用（回填）
│   ├── MenuSessionStore         # uuid → (menuName, inventory)，引用相等校验
│   ├── MenuClickListener        # Click/Drag/Close/Quit 四件套 + 按钮冷却
│   ├── MenuActionParser         # 动作串解析（delay: / 多动作，ALFriends 同款）
│   ├── MenuActionExecutor       # 延迟动作经调度器 runAtEntityDelayed 执行
│   ├── MenuActionRegistry       # Map<String, MenuAction> 动作注册表（禁巨型 switch）
│   └── render/
│       ├── MainMenuRenderer     # 按菜单拆分的渲染器（动态区填充 + PDC 编码）
│       ├── VeteranMenuRenderer
│       └── ShopMenuRenderer
└── util/
    └── ItemUtil                 # material 解析(容错)/PDC 读写/物品构建/头颅
```

---

## 三、统一调度器（硬性要求）

### 3.1 前置：pom 依赖切换

`spigot-api 1.21.3` → **`paper-api 1.20.1-R0.1-SNAPSHOT`（provided）**。
- 两个参考插件均用 paper-api；Paper 1.20.1 API 起就含 `GlobalRegionScheduler/AsyncScheduler/EntityScheduler`，与 plugin.yml `api-version: '1.20'` 一致。
- 换成 paper-api 后调度器**不再需要任何反射**（ALwarp 的写法），运行在 Spigot 上时 Folia 分支永远走不到，兼容性不变。

### 3.2 调度器实现（照 ALwarp `scheduler/FoliaScheduler.java` 落地）

- 静态块一次性探测 `IS_FOLIA`（顺带 `isMohist()` 墨端检测，供 Adventure 回退备用）。
- 四个调度域：`runGlobal / runGlobalDelayed / runGlobalTimer`、`runAtPlayer / runAtPlayerDelayed`（GUI 主战场）、`runAsync`（只取数）、`runAtLocation`（预留）。
- 定时任务返回 `int` 句柄并登记，`cancel(taskId)` / **`cancelAll()`** 齐备；`Math.max(1L, delay)` 钳制；Folia 异步 tick→ms 换算。
- **禁止事项落地**：业务代码不再出现任何 `Bukkit.getScheduler()` / `entity.getScheduler()` 裸调用；删除 `new Thread` 兜底与 `future.join()`。

### 3.3 调用点迁移（机械替换，13 个文件）

| 旧调用 | 新调用（按语义） |
|---|---|
| `SchedulerUtils.runTask(plugin, player, r)` | `scheduler.runAtPlayer(player, r)`（回填/开箱一律实体域） |
| `SchedulerUtils.runTask(plugin, r)` | `scheduler.runGlobal(r)` |
| `SchedulerUtils.runTaskLater(plugin, r, d)` | `scheduler.runGlobalDelayed(r, d)` 或 `runAtPlayerDelayed` |
| `SchedulerUtils.runTaskAsynchronously(plugin, r)` | `scheduler.runAsync(r)` |
| `SchedulerUtils.runTaskTimer*` | 对应 Timer + 句柄登记 |

替换完成后**删除** `FoliaScheduler.java`、`SchedulerUtils.java`、`ThreadPoolManager.java` 三个文件。
`onDisable()` 补：`scheduler.cancelAll()` → 逐个结算/关闭打开中的菜单 → `MenuSessionStore.clearAll()`。

---

## 四、GUI 事件与会话（铁律 3/4/5）

1. **Holder 即身份**：`InviteMenuHolder(menuName, shape)`，建箱后 `holder.setInventory(inv)` 回填真实引用（修拖拽保护）。Listener 里 `instanceof InviteMenuHolder` 命中即 `setCancelled(true)`（Click 与 Drag 都是），危险点击类型（NUMBER_KEY/DOUBLE_CLICK 等）先挡。
2. **会话校验**：`MenuSessionStore.track(uuid, menuName, inv)`；异步回填标准姿势：
   ```
   runAsync(只查库) → runAtPlayer(切回实体线程) → sessionStore.isTracked(uuid, inv) 引用相等? → setItem/openInventory
   ```
   缺一步都不行——根治幽灵弹窗与异步线程碰 Inventory。
3. **事件四件套**：
   - Click：PDC 读动作串 → 冷却防抖 → 注册表分发；
   - Drag：一律取消；
   - Close（MONITOR）：清会话/冷却，结算逻辑在此做（去掉"延迟 1 tick"补丁——改成"Close 时若 1 tick 内开了新菜单则新菜单重新 track，旧会话自然被顶掉"）；
   - PlayerQuit：`clearPlayerState(uuid)`（会话 + 输入状态 + 冷却），`onDisable` 兜底再清一次。
4. **聊天输入修复**：`InputState` 挂真超时任务（`runAtPlayerDelayed`，句柄存状态里，完成时 cancel）；退出即清。
5. `MenuSessionManager` 单例取消，改为插件字段持有的 `MenuSessionStore` 实例。

---

## 五、点击管线（参照 ALFriends）

- **动作编码进物品 PDC**（`gui_action` / `gui_right_action` / `gui_shift_left_action` / `gui_shift_right_action` 四个 NamespacedKey），点击时零配置查询；动态条目的 ID（`milestone_required` / `gift_id`）也写 PDC——**彻底删掉 `slot - 10` 魔法数字**（顺带修复买错礼包）。
- **动作注册表** `Map<String, MenuAction>`，新动作 = 注册一条，不再往 switch 里塞 case：
  - 静态：`open_veteran` / `open_shop` / `open_main` / `close` / `prev_page` / `next_page` / `input_code`
  - 动态：`claim_milestone:%required%` / `buy_gift:%gift_id%` / `switch_gift:%gift_id%`
- **动作串 DSL**（ALFriends `MenuActionParser` 同款）：`sound:…`、`delay:10t`、`message:…`、`console:…`（替代任何提权操作）、多动作 `\u001E` 分隔按序执行。
- **分页**：`Pagination.page(entries, pages, uuid, pageSize)` 纯函数（可单测），pageSize = shape 动态字符槽位数（缓存解析，不再手写 14/21）；翻页只重渲染动态区，不整箱重建。

---

## 六、菜单配置 YAML 化（参照 ALwarp/ALFriends + 规范）

1. **`menus.yml` → `menus/` 目录，一菜一文件**：
   ```
   resources/menus/main_menu.yml  veteran_menu.yml  shop_menu.yml
   resources/menus_en/…           # 双语目录，文件名一一对应
   ```
   菜单名进 `MenuNames` 常量类，禁止散落字符串。
2. **YAML 格式**（块级写法）：
   ```yaml
   config_version: 1
   title: "&6&l邀请中心 &7第 %page%/%total_pages% 页"
   shape:
     - "B###G###A"
     - "#MMMMMMM#"
     - "P#######N"
   items:
     "M":
       dynamic: true
       states:            # 保留现有 locked/available/claimed、available/purchased/current 语义
         locked: { material: …, name: …, lore: … }
     "P":
       material: ARROW
       name: "&a上一页"
       lore:
         - "&7当前页: &e%page%/%total_pages%"
       triggers:
         left: [ "prev_page" ]
   ```
3. **配置升级**：每文件带 `config_version`；磁盘旧版 → 先备份 `<name>.yml.bak` → **只补缺失 key，保留服主改动**；磁盘没有 → 从 jar 释放；检测到旧 `menus.yml` 存在 → 自动搬运生成三个新文件并保留原文件为 `menus.yml.bak`。
4. **启动校验 + 单测**：每行 9 字符、行数 1–6、shape 用到的字符在 items 有定义、动作串非空白、`delay:` 可解析；校验逻辑写成不依赖 Bukkit 的类，配 `MenuConfigurationAuditTest` 直读 `src/main/resources`（对照 zh/en 两套防漂移），进 CI。
5. **reload**：`volatile Map<String, MenuConfig>` + `Map.copyOf` 原子整体替换；加载失败保留旧快照，绝不让 reload 把插件打残。

### Lore / 文案优化（专项）

- **条件 lore 行**：占位符解析失败的行**整行删除**，不再显示 `%xxx%` 原文或空坑。
- **`{reward_lore_N}` 坑位制废除**：改为 `{reward_lore}` 单标记 = 整段奖励描述按行展开（现有 11 个坑位配置缩成 1 行）。
- **Java 里硬编码文案全部出清**：页码提示、状态文字（未解锁/可领取/已领取）、奖励类型行（金币/点券/物品/命令）、输入校验提示 → 语言文件 `languages/zh_cn.yml` / `en_us.yml`，菜单内文案 → `menus/*.yml`。
- 物品构建统一走 `ItemUtil`：material 容错（非法值降级 STONE + 启动警告）、`HIDE_ATTRIBUTES`、custom-model-data、PVC 动作写入一处收口。

---

## 七、实施阶段（每阶段结束都可编译、可运行、行为不变）

| 阶段 | 内容 | 风险 |
|---|---|---|
| **P1 调度器统一** | pom 切 paper-api → 新增 `ALInviteScheduler` → 13 文件机械替换 → 删三个旧类 → onDisable 补 cancelAll | 低（纯等价替换） |
| **P2 Holder/会话修正** | InviteMenuHolder 回填引用、MenuSessionStore 引用校验、异步回填改"async→atPlayer→验会话"、事件四件套补齐（Quit 清理、输入超时任务）、删单例 | 低-中 |
| **P3 配置迁移** | `menus/` 目录 + MenuConfigLoader（校验/合并升级/旧 menus.yml 自动迁移备份）+ reload 快照化 + 审计单测 | 中（要测服主旧配置迁移） |
| **P4 点击管线** | PDC 动作编码 + 动作注册表 + 冷却 + Pagination（**修复买错礼包/页码错误两个 bug**）+ 翻页局部重渲染 | 中 |
| **P5 渲染拆分与文案** | 三个 Renderer 拆出 MenuManager、lore 条件行/`{reward_lore}` 展开、硬编码文案进语言文件、`menus_en/` 双语 | 低 |
| **P6 收尾** | 删死代码（`createInventory`、`createCurrentGiftPreviewSync`、重复 `buildSlotActions`）、更新 README/WIKI、全量回归 | 低 |

每阶段一个 commit，P1–P2 完成后先出一版可上服验证的 jar，再继续 P3–P6。

---

## 八、兼容性承诺（"没有影响"）

1. **指令、权限、占位符（%alinvite_%）、API（ALInviteAPI）、事件（ThirdPartyPointsRechargeEvent）全部不变。**
2. 服主侧零手工迁移：旧 `menus.yml` 首次启动自动拆分为 `menus/*.yml` 并备份原文件；已自定义的 lore/布局/动作原样保留进新结构。
3. 默认外观与现版本一致（布局、物品、lore 文案原样搬运）；仅修复下述 bug 时行为才有变化。
4. 服务端兼容：Paper/Spigot/Folia 由统一调度器静态探测自动分支；`folia-supported: true`、`api-version: '1.20'` 不变。

## 九、顺带修复的真 bug（重构收益）

1. Paper 1.21+ 拖拽保护失效风险（Holder 返回 null）——P2
2. 礼包商店第二行点错礼包（`slot-10`/`giftsPerPage=7` 与 14 格布局不符）——P4
3. 里程碑页码上限算错（`milestonesPerPage=14` 与 21 格布局不符）——P4
4. 关菜单后异步数据回来把菜单幽灵弹回（无会话校验）——P2
5. `createCurrentGiftPreviewSync` 在调用线程 `.join()` 阻塞（死代码，直接删）——P6
6. 聊天输入状态：无真超时任务、退出不清、`onDisable` 不清——P2
7. 菜单材质写错直接抛异常（`Material.valueOf` 无容错）——P5
8. 停服不回收调度任务——P1

---

## 十、性能与数据层重构清单（GUI 之外的全面审查）

### A. 性能问题（按严重度排序）

#### A1.【最严重】菜单渲染 = 主线程数据库查询风暴
- `PlaceholderResolver.applyPlaceholders(text, player)`（同步方法）被 `MenuManager` 对**每个按钮名、每行 lore** 调用；每次调用执行 11 个 `getXxxSync(uuid)`，其中 **4 个无任何缓存、每次真查库**：`getBindStatusSync`（join×1）、`getInviterNameSync`（join×2）、`getTotalRebateSync`（join×1）、`getContributionSync`（join×1）；其余在缓存 miss 时也 join 查库。
- 估算：veteran 菜单 21 个里程碑槽 × 约 12 行 lore ≈ 250 行文本 × 11 次 Sync 调用，其中约 1/3 是无缓存真实查库 → **每打开一次菜单，主线程阻塞执行上千次数据库往返**。这是"打开菜单卡"的直接原因。
- `placeholder/PlaceholderHook`（PAPI 扩展）同病：`onRequest` 里 `.join()` 查库（inviter/getPlayerData 无缓存），PAPI 请求跑在调用方线程（通常主线程），被记分板/浮字类插件高频调用时主线程持续打 DB。
- **修法**（参照 ALwarp `PlaceholderUtil.RenderContext` / ALFriends `PlaceholderRenderContext`）：
  1. 每次菜单打开时**异步一次性**取齐该玩家全部占位符数据（已有 `resolveAllPlaceholders` 骨架），构建不可变 `RenderContext`（Map）；
  2. 文本渲染只做 Map 替换（零查库、零 join）；
  3. PAPI 内置占位符改走 CacheManager 短 TTL 缓存（30~60s），杜绝主线程查库；
  4. PAPI 第三方占位符解析仍在渲染时做，但每菜单每物品一次，不逐行重复。

#### A2. 全插件 CompletableFuture 用 ForkJoinPool.commonPool + 23 处 `.join()` 阻塞
- 所有 `supplyAsync/runAsync` 未指定 executor：**1~2 核 VPS 上 commonPool parallelism≈0，"异步"直接退化为提交线程同步执行——DB 查询落到主线程**。
- `.join()` 共 23 处，其中**嵌套 join**（在 supplyAsync 内部再 join 别的任务）：`InviteManager:50`（生成邀请码循环）、`GiftManager:394/420`、`PlaceholderResolver` 全部 Async 方法（`supplyAsync(() -> xxxSync)` 包装模式）、`ALInviteAPI` 4 处、`PointsRebateManager` 6 处、`CommandHandler.handleStats` 2 处、`InviteListener:35`。commonPool 嵌套 join 有饥饿/死锁风险（尤其 PointsRebateManager 里 commonPool 线程 `future.get()` 等主线程执行命令）。
- **修法**：插件自建命名 `ExecutorService`（DB/IO 专用，线程数 2~4 可配），所有 DB 异步显式指定该池；嵌套 join 全部改 `thenCompose` 链；奖励/消息写回一律经统一调度器 `runAtPlayer` 切回。`ThreadPoolManager` 的死线程池顺势改造成这个真正的 IO 池（物尽其用，而不是删除后新造）。

#### A3. 异步线程直接操作玩家背包 / Vault 经济
- `GiftManager.giveGiftRewards → giveItem`：`player.getInventory().addItem()` + `world.dropItem()` 跑在 commonPool（调用链：`InviteManager.doBindInviteRecord.thenApply`，异步）；`MilestoneManager.giveRewards → giveItem` 同样（`checkMilestones` 从异步链与 `InviteListener` 的 join 异步任务调用）；`VaultEconomyUtils.deposit` 也在异步链直接调用。
- `GiftManager.buyGift`：`runTaskSupplied`（内部 `future.join()` 阻塞等待实体线程）内又 `.join()` 三个 DB 任务——**commonPool 等实体线程、实体线程等 commonPool** 的互相等待结构，死锁风险 + 线程占用。
- **修法**：新建统一 `RewardService`（奖励发放唯一入口：command/money/points/item 四类），内部保证所有发放先 `runAtPlayer` 切实体线程；`buyGift` 改纯异步链（经济操作切实体线程、DB 走 IO 池），消灭全部 join。Milestone/Gift 两份重复的 giveItem/givePoints/PlayerPoints 反射代码合并进 RewardService。

#### A4. PointsRebateManager：同步逻辑包异步壳
- `processRecharge` 整体 `supplyAsync` 包同步代码，内部 6 处 `join()/get()`；`getRebateRate` 每次充值打 3 条 INFO 日志（刷屏）。
- **修法**：全链 thenCompose 化；日志降 debug；`executePointsCommand` 的"异步线程等主线程"改为挂起-回调（CompletableFuture 返回，不 get）。

#### A5. 空转与低效杂项
- `syncAnnouncements()` 是空壳（return true），但 `scheduleAnnouncementSync` 每 5 分钟调度一次（跨服公告功能半成品：`getUnbroadcastedAnnouncements/markAnnouncementBroadcasted` 无人调用）→ 移除空转调度（或实现完整功能，待定）。
- `ConfigManager.colorize`：`replace("&","§")` 会误伤正文普通 `&`；每行文本走 MiniMessage deserialize + legacy serialize 两趟完整解析（GUI 高频）。与 `utils/ColorUtil` 双实现并存 → 统一收口到 ColorUtil（`translateAlternateColorCodes`），MiniMessage 仅用于显式 `<tag>` 文本；PAPI 存在性判断缓存化。
- `AutoVeteranManager.loadConfig → startScheduler` 与 `LeaderboardManager` 的定时任务**无句柄**：`/alinvite admin reload` 后旧定时器不取消、多实例并行跑（任务泄漏）→ P1 句柄化时一并修（Manager 持 taskId，重建前 cancel）。
- `DatabaseManager.getTopPlayersByInvites/Contribution`：SELECT * + 19 字段映射两份复制粘贴 → `mapRow` 抽取（顺手只取需要列）。

### B. 正确性 bug（顺带修复）

| # | 问题 | 位置 | 修法 |
|---|---|---|---|
| B1 | 防重复充值**半成品**：`checkAntiDuplicate` 查 `transaction_key` 是否 PROCESSED，但写入/标记方法（`addPointsRebateRecord/markPointsRebateRecordProcessed`）**无人调用** → 永不拦截；且 key=uuid+金额，一旦补上写入，同日同金额两笔充值会被误判 | PointsRebateManager:101-125 | 本期先**明确禁用并注释**（保持现行为），或补全写入+key 加时间窗（属行为变化，实施前与服主确认） |
| B2 | `getTodayRebateTotal` 恒返 0 → 每日返点上限永不生效 | DatabaseManager:1190 | 按天聚合查询实现（行为变化点，同上确认） |
| B3 | `/alinvite stats` 把已领取里程碑的**原始 JSON** 塞进消息（显示 `["1","3"]`） | CommandHandler:151-164 | 解析为友好文本（1、3 之类） |
| B4 | `generatingCodes` 用 `IdentityHashMap` 包装 Set，UUID 值相等失效 → 防并发生成邀请码基本失效 | InviteManager:19 | 改 `ConcurrentHashMap.newKeySet()` |
| B5 | DB 重连路径：`init()` 直接覆盖 `dataSource`，**旧 HikariDataSource 不 close**（连接/文件句柄泄漏）；重连时 `Thread.sleep(5s)` 阻塞调用线程；`reconnectAttempts` 非原子 | DatabaseManager:64-99 | close 旧池再建新池；重连移出热路径（fail-fast + 下次调用重试）；AtomicInteger |
| B6 | 伪 JSON 列的 read-modify-write **无锁**：`addAnnouncedMilestone / addPurchasedGift / addClaimedPermissionGroup` 并发写丢更新（claimMilestone 有 per-UUID 锁，其余没有） | DatabaseManager | 同款 per-key 锁 |
| B7 | `Material.valueOf` 无容错：`GiftManager.loadGifts` 配置错材质直接炸启动；MenuManager 渲染多处同理 | GiftManager:64 等 | 统一 ItemUtil 容错（原 P5 项，扩大覆盖） |
| B8 | `InviteListener.onPlayerJoin` 异步任务里对玩家**发放待领奖励**（碰背包）+ `.join()` | InviteListener:34-44 | 并入 RewardService + 异步链改造（A2/A3） |

### C. 结构清理（间接性能收益）

- `GiftManager.handleSlotPurchase` 与 `MenuListener.handleBuyGift` 是**同一逻辑两份实现**（前者用 `giftSlots.indexOf(slot)` 是对的，后者 `slot-10` 是错的）→ P4 合并为 ShopService 一份。
- `CommandHandler` admin contrib add/set/deduct/clear/h 五段复制粘贴，且一半文案硬编码一半走语言文件 → 收敛 + 文案出清（并入 P5）。
- `ConfigManager.updateMissingMenus` 补的 `gift_shop / milestone_rewards` 节在 menus.yml 里**根本不存在**（历史遗留死代码）；`updateMissingLang` 硬编码 4 个历史 key → P3 的 MenuConfigLoader 重写统一收口。
- `DatabaseManager` 方法别名族（updateMoney/updateInviteCount/updatePermissionGroup/addInviteRecord/getInviter/checkCrossServerDuplicate/updateContributionAmount 重载…）收敛为单一入口；`e.printStackTrace()` 统一改 logger。
- `PointsRebateManager.getActualRebateRate` 死代码 → 删。
- API/行为不变：`ALInviteAPI`、`PlaceholderHook` 的占位符名、事件签名全部保持（内部改缓存/快照实现）。

### D. 阶段计划更新（原六阶段 → 八阶段）

| 阶段 | 内容 | 状态 |
|---|---|---|
| P1 | 统一调度器（含定时任务句柄化，修 A5 定时器泄漏） | 进行中 |
| P2 | Holder/会话修正 + 事件四件套 | 待做 |
| P3 | menus/ 配置迁移 + Loader（含 ConfigManager 死代码清理） | 待做 |
| P4 | 点击管线 + 分页修复 + ShopService 合并两份购买逻辑 | 待做 |
| P5 | 渲染拆分 + lore/文案出清 + ColorUtil 统一 | 待做 |
| P6 | 收尾/回归/文档 | 待做 |
| **P7（新）** | **RewardService**：奖励发放统一入口、切实体线程（修 A3/B8）；buyGift 异步链化（修 A2 尾巴） | 待做 |
| **P8（新）** | **数据层收口**：IO 线程池指定 executor（修 A2）、嵌套 join 全清、PlaceholderResolver/PlaceholderHook 快照+缓存化（修 A1）、DB 锁与重连修复（B5/B6）、PointsRebate thenCompose 化与死代码清理（A4/B1/B2/C 项） | 待做 |

> B1/B2 涉及行为变化（防重复与每日上限会开始真正生效），实施前单独列出与服主确认；其余项均为行为不变的性能/健壮性修复。
