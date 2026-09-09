# ALInvite API 文档

## 📖 概述

ALInvite 提供完整的 API 接口，支持通过 Java API 和命令调用两种方式集成。第三方插件可以轻松获取玩家邀请数据、触发返点处理、查询排行榜等功能。

## 🚀 快速开始

### 📦 导入 API

```java
import com.alinvite.api.ALInviteAPI;
import com.alinvite.ALInvite;
```

### 🔧 获取插件实例

```java
// 方式1：通过 Bukkit 获取（推荐）
ALInvite plugin = (ALInvite) Bukkit.getPluginManager().getPlugin("ALInvite");

// 方式2：通过 API 类获取
ALInvite plugin = ALInviteAPI.getPlugin();
```

## 🔌 API 方法详解

### 🔑 邀请码相关方法

#### 获取玩家邀请码
```java
// 异步获取玩家邀请码
CompletableFuture<String> getInviteCode(Player player);
CompletableFuture<String> getInviteCode(UUID uuid);

// 使用示例
ALInviteAPI.getInviteCode(player).thenAccept(code -> {
    if (code != null) {
        player.sendMessage("你的邀请码: " + code);
    } else {
        player.sendMessage("你还没有邀请码");
    }
});
```

#### 检查邀请码有效性
```java
// 检查邀请码是否有效
CompletableFuture<Boolean> isInviteCodeValid(String code);

// 使用示例
ALInviteAPI.isInviteCodeValid("ABC123").thenAccept(valid -> {
    if (valid) {
        // 邀请码有效
    } else {
        // 邀请码无效
    }
});
```

### 📊 统计相关方法

#### 获取邀请统计
```java
// 获取玩家累计邀请人数
CompletableFuture<Integer> getTotalInvites(Player player);
CompletableFuture<Integer> getTotalInvites(UUID uuid);

// 使用示例
ALInviteAPI.getTotalInvites(player).thenAccept(total -> {
    player.sendMessage("你累计邀请了 " + total + " 名玩家");
});
```

#### 获取返点统计
```java
// 获取玩家累计返点
CompletableFuture<Double> getTotalRebate(Player player);
CompletableFuture<Double> getTotalRebate(UUID uuid);
```

### 🎁 礼包相关方法

#### 获取玩家礼包信息
```java
// 获取玩家当前使用的礼包ID
CompletableFuture<String> getPurchasedGift(Player player);
CompletableFuture<String> getPurchasedGift(UUID uuid);

// 获取礼包配置信息
CompletableFuture<GiftManager.GiftConfig> getActiveGift(Player player);
CompletableFuture<GiftManager.GiftConfig> getActiveGift(UUID uuid);
```

#### 礼包配置结构
```java
public class GiftConfig {
    private String id;           // 礼包ID
    private String name;         // 礼包名称
    private String material;     // 物品材质
    private double priceMoney;   // 金币价格
    private double pricePoints;  // 点券价格
    private List<Reward> rewards;// 奖励列表
    
    // getter 方法...
}
```

### 🏆 里程碑相关方法

#### 获取里程碑信息
```java
// 获取玩家里程碑进度
CompletableFuture<Map<Integer, MilestoneManager.Milestone>> getPlayerMilestones(Player player);

// 里程碑结构
public class Milestone {
    private int required;        // 所需邀请人数
    private String name;         // 里程碑名称
    private List<String> lore;   // 描述
    private List<Reward> rewards;// 奖励
    private boolean claimed;     // 是否已领取
    
    // getter 方法...
}
```

### 📊 排行榜相关方法

#### 获取排行榜数据
```java
// 获取指定类型的排行榜
CompletableFuture<List<LeaderboardManager.LeaderboardEntry>> getLeaderboard(LeaderboardManager.LeaderboardType type, int limit);

// 排行榜类型
public enum LeaderboardType {
    MILESTONE("milestone", "邀请人数"),
    CONTRIBUTION("contribution", "贡献返点"),
    POINTS_REBATE("points_rebate", "点券返点");
}

// 排行榜条目结构
public class LeaderboardEntry {
    private String playerName;   // 玩家名称
    private UUID playerUUID;     // 玩家UUID
    private double value;        // 数值（邀请人数/返点金额）
    private int rank;            // 排名
    
    // getter 方法...
}
```

### 🔒 安全相关方法

#### IP检查方法
```java
// 检查两个玩家是否同IP
CompletableFuture<Boolean> isSameIp(Player p1, Player p2);

// 检查玩家是否受IP限制
CompletableFuture<Boolean> isIpRestricted(Player player);
```

## 💰 返点处理 API

### 点券充值返点

#### 核心返点方法
```java
// 处理点券充值返点（完整流程）
CompletableFuture<Boolean> processRecharge(String operator, String targetPlayer, double amount, boolean skipRebate);

// 使用示例
ALInviteAPI.processRecharge("CONSOLE", "PlayerName", 100.0, false).thenAccept(success -> {
    if (success) {
        // 返点处理成功
    } else {
        // 处理失败
    }
});
```

#### 参数说明
- `operator`: 操作者名称（"CONSOLE" 或玩家名）
- `targetPlayer`: 目标玩家名称
- `amount`: 充值点券数量
- `skipRebate`: 是否跳过返点处理（用于测试）

### 贡献返点处理
```java
// 处理贡献返点
CompletableFuture<Boolean> processContributionRebate(String operator, String targetPlayer, double amount);
```

## 🔗 事件监听 API

### 邀请成功事件监听

#### 注册事件监听器
```java
// 注册邀请成功事件监听器
ALInviteAPI.registerInviteSuccessListener(new ALInviteAPI.InviteSuccessListener() {
    @Override
    public void onInviteSuccess(String inviterName, String newPlayerName, String inviteCode) {
        // 处理邀请成功逻辑
        Bukkit.broadcastMessage(inviterName + " 成功邀请了 " + newPlayerName + "！");
    }
});
```

#### 移除事件监听器
```java
// 移除事件监听器
ALInviteAPI.unregisterInviteSuccessListener(listener);
```

## 🖥️ GUI 相关 API

### 打开菜单方法
```java
// 为玩家打开指定菜单
boolean openMenu(Player player, MenuManager.MenuType menuType);

// 菜单类型
public enum MenuType {
    MAIN,           // 主菜单
    VETERAN,        // 邀请中心
    GIFT_SHOP,      // 礼包商店
    MILESTONE       // 里程碑菜单
}
```

## 📊 PlaceholderAPI 支持

### 可用占位符列表

| 占位符 | 描述 | 返回值示例 |
|--------|------|------------|
| `%alinvite_code%` | 玩家邀请码 | `ABC123` |
| `%alinvite_total%` | 累计邀请人数 | `5` |
| `%alinvite_gift%` | 当前礼包名称 | `基础礼包` |
| `%alinvite_rebate%` | 累计返点 | `150.5` |
| `%alinvite_milestone_进度%` | 里程碑进度 | `3/5` |
| `%alinvite_rank_类型%` | 排行榜排名 | `1` |

### 自定义占位符注册
```java
// 注册自定义占位符（高级用法）
PlaceholderHook placeholderHook = plugin.getPlaceholderHook();
// 占位符会自动注册到 PlaceholderAPI
```

## 🔧 命令调用集成

### 通过命令调用集成

ALInvite 支持通过命令调用方式与第三方插件集成。这是最简单直接的集成方式。

#### 配置示例
```yaml
# 第三方插件配置示例
commands:
  # 支付完成后执行返点命令
  - '[console]alinvite givedj %player_name% %points%'
  
  # 可选：给玩家发送成功消息
  - '[message]&a充值成功！返点已自动处理'
```

#### 可用命令

| 命令 | 权限 | 描述 |
|------|------|------|
| `alinvite givedj <玩家> <金额>` | `alinvite.admin` | 给玩家发放点券返点 |
| `alinvite givecontribution <玩家> <金额>` | `alinvite.admin` | 给玩家发放贡献返点 |
| `alinvite stats <玩家>` | `alinvite.admin` | 查看玩家邀请统计 |
| `alinvite reload` | `alinvite.admin` | 重载插件配置 |

#### 变量说明
- `%player_name%` - 玩家名称
- `%points%` - 充值点券数量

#### 执行流程
1. 第三方插件执行 `[console]alinvite givedj 玩家名 金额`
2. ALInvite 处理返点逻辑
3. 自动计算返点比例并发放奖励
4. 记录返点历史

## ⚡ 异步处理说明

### 异步方法使用

所有 API 方法都返回 `CompletableFuture`，支持异步处理：

```java
// 异步处理示例
ALInviteAPI.getTotalInvites(player).thenAccept(total -> {
    // 在主线程中处理结果
    Bukkit.getScheduler().runTask(plugin, () -> {
        player.sendMessage("你的邀请人数: " + total);
    });
}).exceptionally(throwable -> {
    // 错误处理
    plugin.getLogger().warning("获取邀请人数失败: " + throwable.getMessage());
    return null;
});
```

### 线程安全

- 所有数据库操作都在异步线程中执行
- GUI 操作会自动切换到主线程
- 缓存操作是线程安全的

## 🛠️ 最佳实践

### 错误处理
```java
ALInviteAPI.getInviteCode(player).thenAccept(code -> {
    if (code == null) {
        // 玩家没有邀请码
        player.sendMessage("你还没有邀请码，请联系管理员");
        return;
    }
    // 正常处理逻辑
}).exceptionally(throwable -> {
    plugin.getLogger().severe("获取邀请码失败: " + throwable.getMessage());
    return null;
});
```

### 性能优化
```java
// 批量获取多个玩家数据
List<CompletableFuture<String>> futures = players.stream()
    .map(ALInviteAPI::getInviteCode)
    .collect(Collectors.toList());

CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
    .thenRun(() -> {
        // 所有数据获取完成
    });
```

## 🐛 调试和日志

### 启用调试模式
```yaml
# 在 config.yml 中启用调试
debug: true
```

### 查看 API 调用日志
```java
// 在代码中记录 API 调用
plugin.getLogger().info("API调用: " + methodName + " - " + parameters);
```

## 📚 示例代码

### 完整的第三方插件集成示例

```java
public class MyEconomyPlugin extends JavaPlugin {
    
    private ALInvite alinvite;
    
    @Override
    public void onEnable() {
        // 获取 ALInvite 实例
        alinvite = (ALInvite) getServer().getPluginManager().getPlugin("ALInvite");
        
        if (alinvite == null) {
            getLogger().warning("ALInvite 未找到，返点功能将不可用");
            return;
        }
        
        // 注册事件监听器
        ALInviteAPI.registerInviteSuccessListener((inviter, newPlayer, code) -> {
            getLogger().info(inviter + " 邀请了 " + newPlayer);
        });
    }
    
    public void processPayment(Player player, double amount) {
        // 通过命令调用处理返点
        String command = String.format("alinvite givedj %s %.2f", player.getName(), amount);
        getServer().dispatchCommand(getServer().getConsoleSender(), command);
    }
}
```

---

**ALInvite API** - 强大的第三方集成支持！ 🚀

---

## 🆕 2.0.0 新增 API

### 📊 数据查询扩展

```java
// 未领取返点数量（手动领取制下的待领取池）
CompletableFuture<Double> ALInviteAPI.getUnclaimedRebate(UUID uuid);

// 贡献返点余额
CompletableFuture<Double> ALInviteAPI.getContribution(UUID uuid);

// 已领取里程碑集合（元素为所需邀请人数）
CompletableFuture<Set<Integer>> ALInviteAPI.getClaimedMilestones(UUID uuid);

// 指定里程碑是否已领取
CompletableFuture<Boolean> ALInviteAPI.hasClaimedMilestone(UUID uuid, int required);

// 待领取里程碑（邀请人离线期间达成的）
CompletableFuture<List<String>> ALInviteAPI.getPendingMilestones(UUID uuid);

// 最近的返利记录（时间倒序），limit 建议不超过 100
CompletableFuture<List<ALInviteAPI.RebateEntry>> ALInviteAPI.getRebateRecords(UUID uuid, int limit);
// RebateEntry: long time() 毫秒时间戳 / double amount() 返点数量 / String sourcePlayer() 充值玩家名

// 礼包剩余天数（无期限 -1，已过期 0）
CompletableFuture<Integer> ALInviteAPI.getGiftRemainingDays(UUID uuid);

// 礼包状态：未购买 / 已购买 / 已过期 / 永久
CompletableFuture<String> ALInviteAPI.getGiftStatus(UUID uuid);

// 下一个未达成里程碑：数值 / 名称（全部达成返回 "MAX"）
CompletableFuture<String> ALInviteAPI.getNextMilestone(UUID uuid);
CompletableFuture<String> ALInviteAPI.getNextMilestoneName(UUID uuid);

// 玩家当前充值返点比例（0.15 = 15%）
// 负值 = 点券模式（玩家可自行领取）；正值 = 现金模式（管理员核销后线下发放）
double ALInviteAPI.getRebateRate(Player player);

// 管理员核销未领取返点（线下发放现金后执行），返回核销金额
double ALInvite.clearUnclaimedRebateSync(UUID uuid);

// 返点比例展示文本（如 "15%" / "20%（贡献模式）"）
String ALInviteAPI.getRebateRateDisplay(Player player);
```

### 🖥️ 服务器标识

```java
String serverId = ALInviteAPI.getServerId();      // 本服唯一 ID（config.yml serverid）
String alias    = ALInviteAPI.getServerAlias();   // 服务器别称（config.yml serverName）
```

### 🎧 事件（Bukkit 标准事件，全局线程触发）

```java
import com.alinvite.api.event.InviteBindEvent;
import com.alinvite.api.event.MilestoneClaimEvent;
import com.alinvite.api.event.RebateGrantEvent;

// 1) 邀请绑定成功
@EventHandler
public void onInviteBind(InviteBindEvent event) {
    Player invitee = event.getInvitee();        // 绑定邀请码的玩家
    UUID inviter   = event.getInviterUuid();    // 邀请人
    String code    = event.getInviteCode();     // 绑定的邀请码
}

// 2) 里程碑领取（可取消：取消后不发放奖励，领取资格仍保留）
@EventHandler
public void onMilestoneClaim(MilestoneClaimEvent event) {
    Player player  = event.getPlayer();
    int required   = event.getRequired();       // 所需邀请人数
    String name    = event.getMilestone().getName();
    event.setCancelled(true);                   // 阻止发放奖励
}

// 3) 充值返利入池（可取消：取消后本次返利不计入未领取池，也不会重复发放）
@EventHandler
public void onRebateGrant(RebateGrantEvent event) {
    Player inviter      = event.getInviter();
    String sourcePlayer = event.getSourcePlayerName(); // 充值的玩家
    double rebate       = event.getRebateAmount();
    boolean pointsMode  = event.isPointsMode();         // true = 点券模式（可自行领取）
    event.setCancelled(true);                           // 阻止本次入池
}
```

### 🔁 邀请成功监听器（旧接口，现已实装）

```java
ALInviteAPI.registerInviteListener((inviterUuid, inviteeUuid) -> {
    // 邀请绑定成功时回调（全局线程）
});
```

### 💳 充值流水键（跨服防重复发放）

```java
// 第三方充值系统请传入自己的订单号/流水号：
// 相同流水号的重复推送只会发放一次返利（跨服共享数据库时同样生效）
ALInviteAPI.processPointsRecharge("我的充值插件", player, 648, false, "order-20260910-0001");
```

不传流水键时，每次调用视为一次独立充值（例如管理员的 givedj 指令）。
