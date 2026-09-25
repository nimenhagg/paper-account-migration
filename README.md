# PaperAccountMigration

[![Build Status](https://github.com/nimenhagg/paper-account-migration/actions/workflows/build.yml/badge.svg)](https://github.com/nimenhagg/paper-account-migration/actions)
[![License: GPL v3](https://img.shields.io/badge/License-GPLv3-blue.svg)](LICENSE)
[![Platform](https://img.shields.io/badge/Platform-Paper%20%7C%20Purpur-brightgreen.svg)]()
[![Minecraft](https://img.shields.io/badge/Minecraft-1.21%2B-orange.svg)]()

一个专为 Minecraft **离线模式（online-mode=false）** Paper 服务器打造的**玩家自助改名数据平移与安全登录交互系统**。

彻底告别“玩家换了新游戏名字进服，背包、成就、进度全丢，需要管理员手动帮找文件迁移”的繁琐运维痛点！

---

## 💡 解决的痛点

在离线服务器中，玩家的唯一身份标识 UUID 是由游戏名字离线计算而来的。一旦玩家：
1. 购买了正版账号并启用了新的正版游戏名；
2. 或者在启动器中更换了更好听的游戏名字；

以新名字进服后，系统会将其视为全新的“空白玩家”，原有背包物品、末影箱、血量经验、多世界背包、粘液科技研究进度全部无法继承。

**PaperAccountMigration** 提供了一条极度安全、玩家自主完成的迁移通道：
> 玩家以新名字进服，输入 `/migrateaccount <旧名字> <旧密码>`，系统在验证旧账号密码哈希正确后，自动在后台将旧账号在原版及各大插件中的所有资产与数据一键原子平移至新名字名下！

---

## ✨ 核心特性

- 🔒 **旧账号密码强校验**：直接对接 `LoginSecurity`，使用对应哈希算法（BCrypt、PBKDF2、SHA256 等）验证旧密码，确保只有真正的号主本人才能发起迁移。
- 📦 **全维度数据一键平移**：
  - **原版世界数据**：背包、血量、经验、饱食度、末影箱（`world/players/data/<uuid>.dat`）；
  - **统计与进度**：统计记录（`stats/`）与成就进度（`advancements/`）；
  - **多世界背包**：支持 `Multiverse-Inventories` 全世界组背包及 `playernames.json` 自动更新；
  - **粘液科技**：支持 `Slimefun 5` 玩家研究数据与个人地标（waypoints）；
  - **方块溯源**：支持 `CoreProtect` 破坏与放置历史记录映射；
  - **登录凭证平移**：直接更新 `LoginSecurity.db`，迁移完成后新名字无缝沿用原旧密码直接登录。
- 🛡️ **工业级安全防线**：
  - **防在线冒领**：目标旧账号若当前正在游戏中，坚决拒绝迁移；
  - **防暴力破解**：内置 IP 失败次数统计，连续输错 5 次密码自动锁定 IP 10 分钟；
  - **原子自动备份**：迁移执行前自动在 `backups/migrations/` 创建带时间戳的完整文件级备份，可随时无损回滚；
  - **审计追踪**：每次迁移均在 `plugins/PaperAccountMigration/migration_audit.log` 记录详细审计日志。
- 💬 **交互式登录向导**：进服未登录时，定时推送带点击补全功能的富文本交互提示（支持 `/login`、`/register`、`/migrateaccount`），对新人和改名老玩家极度友好。

---

## 🛠️ 安装与使用

### 安装
1. 下载最新的 `PaperAccountMigration-1.0.0.jar`。
2. 放入服务器的 `plugins/` 目录中（需依赖 `LoginSecurity`）。
3. 重启服务器即可。

### 指令与权限

#### 玩家指令
```text
/migrateaccount <旧游戏名字> <旧账号密码>
```
*别名：`/migrate`、`/accountmigrate`*
- **权限**：默认所有玩家可用 (`accountmigration.use`)
- **效果**：验证旧密码后执行迁移，随后踢出玩家提示重载，重连即可使用旧密码畅玩。

#### 管理员指令
| 指令 | 描述 | 权限节点 |
|---|---|---|
| `/migrationadmin log [行数]` | 查看最近的迁移安全审计日志 | `accountmigration.admin` (OP) |
| `/migrationadmin reload` | 重载插件配置文件 | `accountmigration.admin` (OP) |

---

## ⚙️ 配置文件说明 (`config.yml`)

```yaml
# PaperAccountMigration 配置文件

security:
  # 连续输错旧密码的最大允许次数
  max-failed-attempts: 5
  # 触发防爆破后的 IP 锁定时间（分钟）
  lockout-minutes: 10

prompts:
  # 是否开启聊天栏未登录交互引导提示
  enable-chat-prompts: true
  # 提示推送间隔（秒）
  prompt-interval-seconds: 10

backup:
  # 每次数据迁移前是否自动生成完整备份
  enable-backup: true
```

---

## 🔨 源码编译

```bash
git clone https://github.com/nimenhagg/paper-account-migration.git
cd paper-account-migration
mvn clean package
```
编译产物位于 `target/paper-account-migration-1.0.0.jar`。

---

## 📄 开源协议与使用规范

本项目基于 **GNU General Public License v3.0 (GPLv3)** 开源，并补充以下约定：

### 1. 允许的行为 ✅
- **服务器商用**：允许任何个人、团队将本插件免费部署在任何**盈利或非盈利**的 Minecraft 服务器中（包括含有内购、赞助的商业服）。
- **学习与自用修改**：允许自行修改源码用于自己的服务器，私下自用无需公开修改后的源码。
- **开源二次分发**：允许基于本项目进行二次开发与传播，但**衍生作品必须同样以 GPLv3 协议完全开源**。

### 2. 严格禁止的行为 ❌
- **禁止直接转售/倒卖**：严禁任何个人或组织将本插件（包括其编译后的 .jar、源代码或微调修改版）作为独立商品、付费资源或捆绑包进行直接销售、付费下载或设置付费门槛。
- **保留原作者署名**：在任何分发版本中均须保留原作者版权信息与开源地址。
