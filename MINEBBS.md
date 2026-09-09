[B]标题建议：[/B]
ALInvite 2.0.0 | 邀请激励系统 | 邀请码 / 里程碑 / 礼包商店 / 充值返利手动领取 / 返利记录 / 自动老玩家 / Folia支持

[HR][/HR]

[CENTER][SIZE=7][B][COLOR=#e67e22]ALInvite 2.0.0[/COLOR][/B][/SIZE]
[SIZE=5][B][COLOR=#3498db]邀请码绑定 / 里程碑奖励 / 礼包商店 / 充值返利中心 / 自动老玩家 / 排行榜 / PlaceholderAPI[/COLOR][/B][/SIZE]
[SIZE=4][COLOR=#95a5a6]不是简单的邀请计数插件，而是一套把拉新、留存、充值返利串成完整闭环的激励体系。[/COLOR][/SIZE]

[B][COLOR=#f1c40f]适用于：生存服 / 养老服 / 经济服 / RPG服 / Folia 或 Luminol 服务端[/COLOR][/B][/CENTER]

[HR][/HR]

[SIZE=5][B][COLOR=#3498db]插件简介[/COLOR][/B][/SIZE]

ALInvite 是一款面向 Paper / Purpur / Spigot / Folia / Luminol 等服务端的邀请激励插件。老玩家拥有专属邀请码，新玩家进服绑定邀请码后，邀请人可以领取里程碑奖励、发放新手礼包，充值返利则以“未领取池”的形式累积，玩家在主菜单一键手动领取，并随时查看每笔返利的时间与来源。

全部功能都有 GUI：主菜单、邀请中心、礼包商店、返利记录四个菜单，lore 采用与 ALFriends 一致的分节风格（▶ 标题 / ▸ 信息行 / • 操作提示），支持 shape 自定义布局、动态槽位状态样式、多语言目录。

[B]2.0.0 为全面重构版本：[/B]统一 Folia 调度器、菜单全部配置化（menus/ 一菜一文件）、修复旧版打开菜单卡顿（主线程数据库查询风暴）与多个界面 bug，并新增“充值返利手动领取 + 返利记录”体系。

[LIST]
[*]老玩家专属邀请码，新玩家绑定后双方自动进入激励流程
[*]里程碑奖励：命令 / 金币 / 点券 / 物品四种奖励类型，支持自动领取或手动领取
[*]邀请人离线也不丢单：里程碑进入待领取队列，上线自动补发
[*]新手礼包跟随邀请人的礼包档位，新玩家进服即得奖励
[*]礼包商店：金币 + 点券双价格、有效期、已购买礼包随时切换
[*]充值返利手动领取：返利先进入未领取池，主菜单 S 键一键领取
[*]返利记录菜单：每笔返利的发放时间、金额、来源玩家，分页浏览
[*]返点比例按权限组权重计算，返利统一进入未领取池：点券模式可自行领取，现金模式由管理员核销后线下发放
[*]权限组奖励：LuckPerms 实时监听 + 轮询兜底，被邀请人升级权限组时奖励邀请人
[*]自动老玩家：通过 PlaceholderAPI 在线时长变量自动授予邀请资格
[*]排行榜 + 全套 PlaceholderAPI 变量，方便接入记分板、全息、浮字
[*]SQLite / MySQL 双数据库，HikariCP 连接池，自动建表建索引、自动补列
[/LIST]

[HR][/HR]

[SIZE=5][B][COLOR=#3498db]核心亮点[/COLOR][/B][/SIZE]

[TABLE]
[TR][TH]模块[/TH][TH]说明[/TH][TH]适合场景[/TH][/TR]
[TR][TD]邀请码系统[/TD][TD]邀请码长度、字符集、前缀可配置；数据库查重 + 防并发生成，重复码自动重试[/TD][TD]拉新活动、邀请裂变[/TD][/TR]
[TR][TD]邀请绑定[/TD][TD]同 IP 邀请人数上限、邀请人与被邀请人同 IP 检测；灵活模式可按里程碑 / 返点 / 礼包三项独立放行同 IP 绑定[/TD][TD]防刷小号、防自邀[/TD][/TR]
[TR][TD]里程碑系统[/TD][TD]按累计邀请人数配置任意档位，每档独立奖励列表；auto_claim 控制自动 / 手动领取；已领取状态在 GUI 中以样式区分[/TD][TD]长期留存目标、阶段性激励[/TD][/TR]
[TR][TD]离线补发[/TD][TD]邀请人离线时达成的里程碑进入待领取队列，上线自动补发奖励[/TD][TD]保证奖励不丢失[/TD][/TR]
[TR][TD]新手礼包[/TD][TD]新玩家绑定后，按邀请人当前礼包档位给新人发放命令 / 金币 / 点券 / 物品奖励[/TD][TD]新人前中期体验[/TD][/TR]
[TR][TD]礼包商店[/TD][TD]礼包金币 + 点券双价格、自定义材质模型与lore、有效期天数；已购礼包可随时切换生效[/TD][TD]经济消耗、商城道具[/TD][/TR]
[TR][TD]充值返利中心[/TD][TD]2.0.0 起返利不再自动入账：返利进入未领取池，主菜单 S 键显示累计与未领取数量，左键手动领取计入贡献返点余额[/TD][TD]提高回访率、活跃度[/TD][/TR]
[TR][TD]返利记录[/TD][TD]每笔返利写入记录表：发放时间、金额、来源玩家，GUI 时间倒序分页展示[/TD][TD]让玩家对收益心中有数[/TD][/TR]
[TR][TD]返点比例[/TD][TD]按权限组权重从高到低匹配返点比例，所有返利统一进入未领取池并写入返利记录、累加累计返点；点券模式玩家可自行领取，现金模式由管理员核销后线下发放[/TD][TD]VIP 体系、充值体系联动[/TD][/TR]
[TR][TD]权限组奖励[/TD][TD]被邀请人权限组升级时奖励邀请人金币或点券；LuckPerms 实时监听，无 LuckPerms 时自动轮询检测；邀请人离线奖励自动补发[/TD][TD]与等级 / 会员体系联动[/TD][/TR]
[TR][TD]自动老玩家[/TD][TD]通过 PAPI 在线时长变量检测，达到时长自动执行授权命令（如 LuckPerms 授权），并自动生成邀请码[/TD][TD]放宽邀请资格、激励在线时长[/TD][/TR]
[TR][TD]排行榜[/TD][TD]邀请数 / 贡献返点 / 累计返点三种榜，自动定时刷新，PAPI 输出前 10 名玩家名与数值、玩家本人排名[/TD][TD]全息榜、记分板、竞争氛围[/TD][/TR]
[TR][TD]多语言 GUI[/TD][TD]menus/ 一菜一文件，menus/ 与 menus_en/ 双语目录成套审计；shape 布局、动态槽位、状态样式、triggers 多动作全部 YAML 化[/TD][TD]中外玩家混合服、深度自定义[/TD][/TR]
[TR][TD]高性能[/TD][TD]菜单数据异步快照后回实体线程填充；占位符缓存回源；独立 IO 线程池承载全部数据库查询[/TD][TD]大服 / 低配机器友好[/TD][/TR]
[TR][TD]Folia 兼容[/TD][TD]统一调度器自动探测 Folia / Paper / Spigot，任务句柄统一登记与回收[/TD][TD]Folia / Luminol 区域线程服务端[/TD][/TR]
[/TABLE]

[HR][/HR]

[SIZE=5][B][COLOR=#3498db]玩家如何使用[/COLOR][/B][/SIZE]

[B]打开主菜单：[/B]

[CODE]
/alinvite
/ali
/invite
[/CODE]

[B]邀请流程：[/B]

[LIST]
[*]老玩家在主菜单查看自己的邀请码（也可用 /alinvite code 直接查看），发给新玩家
[*]新玩家进服后点击“填写邀请码”输入邀请码，或使用 /alinvite bind <邀请码>
[*]绑定成功：邀请人邀请数 +1 并触发里程碑检测；新玩家按邀请人礼包档位获得新手礼包
[*]邀请人在“邀请中心”查看进度、领取里程碑奖励
[*]邀请人可在“礼包商店”购买礼包，提升之后邀请新人的奖励档位
[/LIST]

[B]充值返利（2.0.0 新体系）：[/B]

[LIST]
[*]你邀请的玩家充值点券时，你按权限组比例获得返利
[*]返利不再自动到账，而是进入“未领取池”，并写入返利记录
[*]主菜单 S 键“充值返利”显示累计返点与未领取数量
[*]左键一键领取，未领取金额计入贡献返点余额
[*]右键打开“返利记录”，查看每笔返利的发放时间、金额与来源玩家
[/LIST]

[B]小提示：[/B]

[LIST]
[*]贡献返点余额可通过 /alinvite contrib 查询，用于后期兑换
[*]里程碑全部领取后再次点击会明确提示，已领取的里程碑在 GUI 中有独立样式
[*]GUI 内所有按钮都带操作说明（左键 / 右键分别做什么），不需要记命令
[/LIST]

[HR][/HR]

[SIZE=5][B][COLOR=#3498db]里程碑配置[/COLOR][/B][/SIZE]

里程碑在 `config.yml` 的 `milestones` 节配置，档位键就是所需邀请人数，奖励支持命令 / 金币 / 点券 / 物品：

[CODE]
milestones:
  auto_claim: false        # 达标后自动发放；false 则玩家在 GUI 手动领取
  '3':
    name: '&6&l三人同行'
    rewards:
      - type: 'money'
        value: 1000
      - type: 'command'
        value: 'crate give %player% common 1'
    lore:
      - '&7三人成团，奖励多多'
  '10':
    name: '&c&l十全十美'
    rewards:
      - type: 'points'
        value: 100
      - type: 'item'
        value: 'DIAMOND 8'
[/CODE]

[LIST]
[*]GUI 中的进度、状态（未解锁 / 可领取 / 已领取）、奖励预览全部自动生成
[*]状态样式（材质 / 名称 / lore）可在菜单文件 states 里逐状态自定义
[*]奖励预览不需要再预占 {reward_lore_0}~{reward_lore_10} 坑位，一个占位符整段展开
[/LIST]

[HR][/HR]

[SIZE=5][B][COLOR=#3498db]充值返利与手动领取[/COLOR][/B][/SIZE]

[B]返点比例按权限组权重匹配：[/B]

[CODE]
points_rebate:
  permission_prefix: 'alinvite.rebate'
  points_command: 'points give {player} {amount}'
  limits:
    min_amount: 10.0          # 单笔充值最低金额
  rebate_rates:
    contribution:
      rate: 0.20              # 20% 返点
      weight: 20              # 权重高优先匹配
      # （模式由上方 points_rebate.mode 全局设置，权限组只管比例和权重）
    vip:
      rate: 0.15
      weight: 10
    base:
      rate: 0.05              # 无任何权限组的玩家走基础比例
      weight: 0
[/CODE]

[B]两种返利模式：[/B]

[TABLE]
[TR][TH]模式[/TH][TH]到账方式[/TH][TH]返利记录[/TH][/TR]
[TR][TD]点券模式（mode: 'points'）[/TD][TD]进入未领取池，玩家在主菜单 S 键手动领取，计入贡献返点余额（管理员可用 contrib h 兑换为点券）[/TD][TD]写入记录，累计返点同步累加[/TD][/TR]
[TR][TD]现金模式（mode: 'cash'）[/TD][TD]进入未领取池但玩家不可自行领取；管理员用 unclaimed clear 核销后线下发放现金[/TD][TD]写入记录，累计返点同步累加[/TD][/TR]
[/TABLE]

[B]第三方充值接入：[/B]

[CODE]
/alinvite givedj <玩家> <点券数量> [-norebate]
[/CODE]

其他插件可通过 ALInviteAPI 的 `processPointsRecharge` 方法直接推送充值事件触发返点，配合 `ThirdPartyPointsRechargeEvent` 可与任意充值系统对接。

[HR][/HR]

[SIZE=5][B][COLOR=#3498db]自动老玩家[/COLOR][/B][/SIZE]

没有邀请资格的老玩家，可通过在线时长自动晋级：

[CODE]
auto_veteran:
  enabled: true
  playtime_placeholder: '%playtime_time_amount%'
  playtime: '10h'            # 支持 30s / 5m / 10h / 纯数字
  value_unit: hours
  check_interval: 300        # 检查间隔（秒）
  grant_command: 'lp user {player} permission set alinvite.veteran true'
[/CODE]

[LIST]
[*]时长占位符可对接任意提供在线时长的 PAPI 变量（如 PlayTime 插件）
[*]达到时长后自动执行授权命令，并主动为玩家生成邀请码
[*]每次重载会先取消旧检测任务再按新配置重启，不会重复检查
[/LIST]

[HR][/HR]

[SIZE=5][B][COLOR=#3498db]自定义菜单[/COLOR][/B][/SIZE]

菜单全部配置化：`menus/` 目录一菜一文件（主菜单 / 邀请中心 / 礼包商店 / 返利记录），`menus_en/` 为英文对应目录，`language.locale` 设为 en_us 时自动切换。

[CODE]
veteran_menu:
  title: "&6&l邀请中心"
  shape:                          # 每行 9 字符，# 背景，字母对应按钮（建议全部加引号）
    - '#########'
    - 'B###G###H'
    - '#MMMMMMM#'
    - '#MMMMMMM#'
    - '#MMMMMMM#'
    - 'P##X#R##N'
  items:
    M:
      dynamic: true               # 动态槽位：里程碑按页填充
      material: 'GOLD_BLOCK'
      name: '&6&l%milestone_name%'
      lore:
        - '&6▶ &8里程碑'
        - '&e▸ &7邀请进度: &e%current%&7/&a%required%'
        - ''
        - '&6▶ &8奖励预览'
        - '%reward_lore%'
      states:                     # 状态样式：locked / available / claimed
        available:
          material: 'GOLD_INGOT'
          name: '&6&l%milestone_name%'
      triggers:                   # 左键 / 右键 / Shift 组合分别绑定动作（值建议加引号）
        left:
          - 'claim_milestone:%milestone_required%'
    P:
      material: 'ARROW'
      name: '&a&l上一页'
      triggers:
        left:
          - 'sound: UI_BUTTON_CLICK-1-1'
          - 'prev_page'
[/CODE]

[LIST]
[*]物品材质支持自定义物品前缀：`ce:`（CraftEngine）、`ia:`（ItemsAdder）、`oraxen:`（未安装插件时自动回退原版材质），也支持 `AIR` 让槽位完全留空
[*]背景装饰通过 items 里的 `'#':` 物品定义（与 ALFriends 一致），材质、名称随心配
[*]动作串支持 `delay:`（10t / 500ms / 1.5s）、`sound:`、`message:`、`console:`、`player:`，多条动作按序执行
[*]动态条目支持 states 状态样式（里程碑三态、礼包三态），材质支持 `{material}` 取礼包配置材质
[*]lore 条件行：占位符未命中整行自动删除（例如返利记录没有来源玩家时来源行自动隐藏）
[*]内置动作：open_main / open_veteran / open_shop / open_rebate_history / back（按层级返回上级）/ open_main（返回主菜单）/ prev_page / next_page / close / claim_rebate / claim_milestone / buy_gift / input_code
[*]每个菜单文件带 config_version，升级时只补缺失配置，服主已有修改全部保留
[*]旧版配置升级时整体备份到 backup/ 目录并重新生成默认配置（详见升级说明）
[/LIST]

[HR][/HR]

[SIZE=5][B][COLOR=#3498db]PlaceholderAPI 变量[/COLOR][/B][/SIZE]

[TABLE]
[TR][TH]变量[/TH][TH]说明[/TH][/TR]
[TR][TD]%alinvite_code%[/TD][TD]玩家邀请码（无权限显示未解锁）[/TD][/TR]
[TR][TD]%alinvite_total% / %alinvite_total_invites%[/TD][TD]累计邀请人数[/TD][/TR]
[TR][TD]%alinvite_bind_status%[/TD][TD]绑定状态（已绑定 / 未绑定）[/TD][/TR]
[TR][TD]%alinvite_inviter_name%[/TD][TD]邀请人名称[/TD][/TR]
[TR][TD]%alinvite_gift_name% / %alinvite_gift_status% / %alinvite_gift_remaining_days%[/TD][TD]当前礼包名称 / 状态 / 剩余天数[/TD][/TR]
[TR][TD]%alinvite_next_milestone% / %alinvite_next_milestone_name%[/TD][TD]下一个里程碑数值 / 名称[/TD][/TR]
[TR][TD]%alinvite_remaining_for_next_milestone%[/TD][TD]距离下一个里程碑还差人数[/TD][/TR]
[TR][TD]%alinvite_total_rebate% / %alinvite_contribution%[/TD][TD]累计返点 / 贡献返点余额[/TD][/TR]
[TR][TD]%alinvite_milestone_<人数>%[/TD][TD]指定里程碑是否已达标（true / false）[/TD][/TR]
[TR][TD]%alinvite_top_invite_player_1% 等[/TD][TD]排行榜前 10 名玩家名与数值（invite / contribution / points 三种榜）[/TD][/TR]
[TR][TD]%alinvite_my_invites% / %alinvite_my_contribution% / %alinvite_my_points% / %alinvite_rank_invite% 等[/TD][TD]玩家本人数值与排名[/TD][/TR]
[/TABLE]

[HR][/HR]

[SIZE=5][B][COLOR=#3498db]第三方 API[/COLOR][/B][/SIZE]

其他插件可通过 ALInviteAPI 直接对接邀请体系：

[CODE]
ALInviteAPI.getInviteCode(uuid)            // 查询邀请码
ALInviteAPI.getTotalInvites(uuid)          // 查询邀请人数
ALInviteAPI.getBindStatus(uuid)            // 查询绑定状态
ALInviteAPI.getInviterUuid(uuid)           // 查询邀请人
ALInviteAPI.getActiveGift(uuid)            // 查询当前生效礼包
ALInviteAPI.getTotalRebateAmount(uuid)     // 查询累计返点
ALInviteAPI.processPointsRecharge(玩家, 金额) // 推送充值触发返点
ALInviteAPI.replaceLeaderboardVariables(text, type) // 排行榜变量替换
[/CODE]

充值事件处理完成后可监听 `ThirdPartyPointsRechargeEvent` 做后续逻辑，`src` 中附完整对接示例。

2.0.0 起开放更多 API 与标准 Bukkit 事件：
`getUnclaimedRebate / getRebateRecords / getClaimedMilestones / getNextMilestone / getRebateRate` 等查询接口；
`InviteBindEvent`（绑定成功）、`MilestoneClaimEvent`（里程碑领取，可取消）、`RebateGrantEvent`（返利发放，可取消）三个事件，
配合充值流水键（transactionKey）实现跨服防重复发放，详见 `ALInviteAPI.md`。

[HR][/HR]

[SIZE=5][B][COLOR=#3498db]安装说明[/COLOR][/B][/SIZE]

[B]推荐环境：[/B]

[TABLE]
[TR][TH]项目[/TH][TH]说明[/TH][/TR]
[TR][TD]Java[/TD][TD]Java 17 及以上（兼容 Java 21 服务端）[/TD][/TR]
[TR][TD]服务端[/TD][TD]Paper / Purpur / Spigot / Folia / Luminol，1.20.1 - 1.21.11[/TD][/TR]
[TR][TD]经济[/TD][TD]可选：Vault + 经济插件（金币），PlayerPoints 或自定义命令（点券）[/TD][/TR]
[TR][TD]PlaceholderAPI[/TD][TD]可选：启用内置变量、自动老玩家时长检测、菜单第三方变量[/TD][/TR]
[TR][TD]LuckPerms[/TD][TD]可选：权限组实时监听与返点比例权限组[/TD][/TR]
[TR][TD]数据库[/TD][TD]内置 SQLite，无需安装；多服共享数据可切换 MySQL[/TD][/TR]
[/TABLE]

[B]安装步骤：[/B]

[LIST]
[*]将 `ALInvite-2.0.0.jar` 放入服务器 `plugins` 文件夹
[*]启动服务器，生成 `plugins/ALInvite/` 配置目录
[*]按需求修改 `config.yml`（里程碑、礼包、返点比例、IP 限制、自动老玩家等）
[*]按需求修改 `menus/` 下的菜单布局与文案
[*]使用 `/alinvite admin reload` 重载配置，或重启服务器
[/LIST]

[B]生成的配置文件：[/B]

[CODE]
plugins/ALInvite/config.yml
plugins/ALInvite/database.yml
plugins/ALInvite/menus/main_menu.yml
plugins/ALInvite/menus/veteran_menu.yml
plugins/ALInvite/menus/shop_menu.yml
plugins/ALInvite/menus/rebate_history.yml
plugins/ALInvite/menus_en/（英文对应）
plugins/ALInvite/languages/zh_cn.yml
plugins/ALInvite/languages/en_us.yml
[/CODE]

[B]从旧版本升级：[/B]

[LIST]
[*]从 1.2.x 升级：旧配置结构已不兼容，首次运行会整体备份到 plugins/ALInvite/backup/ 并重新生成默认配置，请按新格式重新调整菜单与文案
[*]数据库自动补列、自动建表（新增未领取返点池与返利记录表），无需手动处理
[*]指令、权限、PAPI 变量、第三方 API 保持不变
[/LIST]

[HR][/HR]

[SIZE=5][B][COLOR=#3498db]常用命令[/COLOR][/B][/SIZE]

[CODE]
/alinvite [code|bind|stats|contrib|buygift|help]
/alinvite admin <子命令>
/alinvite givedj <玩家> <点券数量> [-norebate]
[/CODE]

[TABLE]
[TR][TH]命令[/TH][TH]说明[/TH][TH]权限[/TH][/TR]
[TR][TD]/alinvite[/TD][TD]打开主菜单[/TD][TD]alinvite.use[/TD][/TR]
[TR][TD]/ali、/invite[/TD][TD]主命令别名[/TD][TD]alinvite.use[/TD][/TR]
[TR][TD]/alinvite code[/TD][TD]查看我的邀请码[/TD][TD]alinvite.use[/TD][/TR]
[TR][TD]/alinvite bind [邀请码][/TD][TD]绑定邀请码（不带参数进入聊天输入）[/TD][TD]alinvite.use[/TD][/TR]
[TR][TD]/alinvite stats[/TD][TD]查看邀请统计与已领里程碑[/TD][TD]alinvite.use[/TD][/TR]
[TR][TD]/alinvite contrib[/TD][TD]查询贡献返点余额[/TD][TD]alinvite.use + 对应返点权限[/TD][/TR]
[TR][TD]/alinvite buygift[/TD][TD]打开礼包商店[/TD][TD]alinvite.buygift[/TD][/TR]
[TR][TD]/alinvite admin reload[/TD][TD]重载全部配置与菜单[/TD][TD]alinvite.admin[/TD][/TR]
[TR][TD]/alinvite admin givecode <玩家>[/TD][TD]为玩家生成邀请码[/TD][TD]alinvite.admin[/TD][/TR]
[TR][TD]/alinvite admin clearcode <玩家>[/TD][TD]清除玩家邀请码[/TD][TD]alinvite.admin[/TD][/TR]
[TR][TD]/alinvite admin addinvite <玩家> <数量>[/TD][TD]增加邀请人数并触发里程碑检测[/TD][TD]alinvite.admin[/TD][/TR]
[TR][TD]/alinvite admin reset <玩家>[/TD][TD]重置玩家邀请数据[/TD][TD]alinvite.admin[/TD][/TR]
[TR][TD]/alinvite admin announce <玩家> <里程碑>[/TD][TD]手动播报里程碑公告[/TD][TD]alinvite.admin[/TD][/TR]
[TR][TD]/alinvite admin rebate <玩家>[/TD][TD]为该玩家打开返利记录菜单[/TD][TD]alinvite.admin[/TD][/TR]
[TR][TD]/alinvite admin checkgroup <玩家>[/TD][TD]手动检查权限组奖励[/TD][TD]alinvite.admin[/TD][/TR]
[TR][TD]/alinvite admin contrib add|set|deduct|clear|h <玩家> <金额>[/TD][TD]贡献返点管理（h 为兑换点券）[/TD][TD]alinvite.admin[/TD][/TR]
[TR][TD]/alinvite admin papi list|scan|test[/TD][TD]PlaceholderAPI 占位符检测工具[/TD][TD]alinvite.admin[/TD][/TR]
[TR][TD]/alinvite givedj <玩家> <数量> [-norebate][/TD][TD]模拟充值发放点券并触发返点[/TD][TD]alinvite.admin[/TD][/TR]
[/TABLE]

[HR][/HR]

[SIZE=5][B][COLOR=#3498db]常用权限[/COLOR][/B][/SIZE]

[TABLE]
[TR][TH]权限[/TH][TH]默认[/TH][TH]说明[/TH][/TR]
[TR][TD]alinvite.use[/TD][TD]所有玩家[/TD][TD]使用主命令与菜单[/TD][/TR]
[TR][TD]alinvite.buygift[/TD][TD]所有玩家[/TD][TD]购买礼包[/TD][/TR]
[TR][TD]alinvite.veteran[/TD][TD]无（配合自动老玩家或手动授权）[/TD][TD]老玩家资格，可生成邀请码与购买礼包[/TD][/TR]
[TR][TD]alinvite.rebate.<组名>[/TD][TD]无[/TD][TD]充值返点比例权限组，按权重取最高[/TD][/TR]
[TR][TD]alinvite.rebate.contribution[/TD][TD]无[/TD][TD]使用 /alinvite contrib 查询贡献返点[/TD][/TR]
[TR][TD]alinvite.admin[/TD][TD]OP[/TD][TD]管理命令[/TD][/TR]
[/TABLE]

[HR][/HR]

[SIZE=5][B][COLOR=#3498db]适合的人群[/COLOR][/B][/SIZE]

[LIST]
[*]你想通过老玩家邀请新玩家来做服内拉新，而不是单纯靠宣传
[*]你希望邀请奖励有阶段性目标（里程碑）、有新手即时反馈（礼包）、有充值分成（返点）
[*]你希望返利由玩家手动领取，把人拉回主菜单，提高菜单与活动曝光
[*]你想给玩家看得到、查得到的返利明细，减少“返点去哪了”的疑问
[*]你有 LuckPerms 等级 / 会员体系，希望权限组升级能反哺邀请人
[*]你想按在线时长自动放开邀请资格
[*]你需要 Folia / Luminol 兼容，或使用 MySQL 多端数据
[/LIST]

[SIZE=5][B][COLOR=#3498db]不太适合的人群[/COLOR][/B][/SIZE]

[LIST]
[*]你只需要一个简单的“绑定码发奖励”脚本，不需要 GUI、礼包、返点体系
[*]你不打算配置任何经济或点券系统，且完全不需要菜单自定义
[*]你希望邀请奖励全部即时到账、不需要玩家手动领取
[/LIST]

[HR][/HR]

[SIZE=5][B][COLOR=#3498db]购买与售后信息[/COLOR][/B][/SIZE]

[TABLE]
[TR][TH]项目[/TH][TH]内容[/TH][/TR]
[TR][TD]插件名称[/TD][TD]ALInvite[/TD][/TR]
[TR][TD]当前版本[/TD][TD]2.0.0[/TD][/TR]
[TR][TD]作者[/TD][TD]Allen_Linong[/TD][/TR]
[TR][TD]推荐安装文件[/TD][TD]ALInvite-2.0.0.jar[/TD][/TR]
[TR][TD]价格[/TD][TD]以本资源页标价为准[/TD][/TR]
[TR][TD]授权方式[/TD][TD]请按实际填写：单服授权 / 群组服授权 / 定制授权[/TD][/TR]
[TR][TD]售后方式[/TD][TD]请按实际填写：MineBBS 私信 / QQ / 售后群[/TD][/TR]
[TR][TD]下载方式[/TD][TD]购买后在 MineBBS 资源页下载[/TD][/TR]
[/TABLE]

[COLOR=#e67e22][B]购买前建议：[/B][/COLOR]

[LIST]
[*]确认服务端为 Paper / Purpur / Spigot / Folia / Luminol 等兼容核心
[*]金币相关功能需要 Vault 与经济插件；点券功能需要 PlayerPoints 或可用的自定义点券命令
[*]自动老玩家与菜单第三方变量需要 PlaceholderAPI 及对应的时长变量插件
[*]点券充值返点需要服务器有点券体系（PlayerPoints / 任意可用命令发放的点券插件）
[*]更新前建议备份 `plugins/ALInvite/` 配置目录与数据库
[/LIST]

[HR][/HR]

[SIZE=5][B][COLOR=#3498db]更新与配置注意事项[/COLOR][/B][/SIZE]

[LIST]
[*]2.0.0 起充值返利为手动领取制：所有返利统一进入未领取池；点券模式玩家可在主菜单 S 键领取，现金模式需管理员核销（unclaimed clear）后线下发放。此前已直接计入贡献余额的历史数据不受影响
[*]里程碑、礼包、返点比例在 config.yml；菜单布局文案在 menus/ 目录；消息文案在 languages/
[*]menus/ 下每个文件带 config_version；新体系（v3）之后的正常更新只补缺失配置项，不会覆盖服主已修改的内容，也不会再次重置配置
[*]同 IP 绑定支持 strict（直接禁止）与 flexible（按里程碑 / 返点 / 礼包三项独立放行）两种策略
[*]菜单按钮动作全部走 PDC 编码，点击零配置查询；动态条目（里程碑 / 礼包 / 返利记录）按数据 ID 定位，不存在槽位换算错位问题
[*]/alinvite admin papi scan 可一键扫描菜单中的第三方占位符，便于排查变量来源
[*]1.2.x 升级 2.0.0 无需删除任何数据，数据库结构自动升级
[*]多服集群：各服共享同一 MySQL 并启用 Redis（database.yml）即可获得公告跨服广播、缓存失效同步；里程碑领取为数据库原子抢占、充值返点流水占用，跨服不会二次领取或二次发放
[*]config.yml 的 server 段可配置本服 ID、别称与时区；集群内各服 ID 必须唯一
[/LIST]

[HR][/HR]

[CENTER][SIZE=5][B][COLOR=#2ecc71]ALInvite 2.0.0[/COLOR][/B][/SIZE]
[COLOR=#95a5a6]让每一次邀请都有回报，让每一笔返利都清清楚楚。[/COLOR][/CENTER]
