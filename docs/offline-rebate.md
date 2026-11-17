# 离线邀请人返点（2.2.1）

2.2.1 修复邀请人不在当前后端在线时丢失返点权限的问题。结算按数据库里的邀请人 UUID 查询；配置中的 `alinvite.rebate.vip` 对应 16% 时，具有有效权限的月卡邀请人离线也按 16% 计算。插件没有内置固定的月卡比例，官方默认 vip 比例仍是 15%，现有服主配置会保留。

## 计算方式

邀请人在当前后端在线时，通过 ALInviteScheduler 切到该玩家的实体线程，读取有效 Bukkit 权限。邀请人离线、位于另一后端，或实体任务因退出失效时，异步调用 LuckPerms `UserManager.loadUser(UUID)`，审计已到期的临时节点，再查询有效权限。继承的权限和显式拒绝由 LuckPerms 解析。

离线用户没有世界位置。离线查询使用当前结算后端的 LuckPerms 静态上下文，保留该后端的 server 等限制；不会使用忽略全部上下文的查询方式。因此，月卡返点权限应为全局有效，或在实际结算后端有效。仅限定在某个世界或另一台后端的权限，不保证离线命中。

按 `points_rebate.rebate_rates` 下配置的档位和 weight 从高到低选择第一个拥有权限的档位，base 是无需权限的回退档。权重相同按配置顺序检查。自定义档位名称与 permission_prefix 也适用；零比例仍表示该档不产生返点。

未安装/未启用 LuckPerms、加载失败或超过 10 秒仍未返回时，本次按配置的 `base.rate` 结算并记录 warning。无匹配权限的普通用户正常使用 base，不记录查询失败。插件不保存“最后一次月卡身份”作为永久权益，防止月卡过期后继续获得高档返点。

## 配置示例

```yaml
points_rebate:
  permission_prefix: alinvite.rebate
  rebate_rates:
    base:
      rate: 0.05
      weight: 0
    vip:
      rate: 0.16
      weight: 2
```

月卡有效权限需包含 `alinvite.rebate.vip=true`；可以通过月卡组的临时继承提供。充值 100 时，满足此权限的邀请人获得 16 返点，进入原有未领取池；月卡到期或被明确拒绝后回到基础 5 返点。mode、IP 限制、每日上限和返点领取方式仍使用原配置。

## 升级

停服前备份数据库、配置及旧 JAR，替换为 `ALInvite-2.2.1.jar` 后正常启动。共享数据库的后端统一升级，避免不同后端使用不同的结算逻辑。现有配置合并机制保留自定义比例，无需删除配置目录。本补丁不增加数据表或回算历史返点；历史少算需要另行核对原订单及当时的权益，不能仅凭今天的月卡状态批量补发。

MySQL 的 ALInvite 数据表仍需 InnoDB。该补丁不提供 MyISAM 事务兼容性，也不重写充值命令、领取流程或外部点券发放的故障恢复协议。稳定流水键只用于现有返点去重，调用方不应把重复充值命令当作安全的原始点券重放接口。

API 新增 `CompletableFuture<Double> ALInviteAPI.resolveRebateRate(UUID)`。原来的 `getRebateRate(Player)` 继续用于玩家实体线程上的同步展示。异步查询不得在玩家实体线程通过 join/get 阻塞等待。

本版本的构建、真实 LuckPerms 运行结果和 JAR 校验值见[离线返点验证](offline-rebate-validation.md)。
