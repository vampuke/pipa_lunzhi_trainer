# 账号与活跃度云同步（设计定稿）

目标：为轮指大作战加入账号体系，使练习活跃度可跨设备登录、同步；同一账号允许多设备同时在线；会话有效期 180 天，任何带凭证的请求自动续期；本次升级不得丢失既有本机活跃度，并在首次登录时自动合并到云端。后端用 Cloudflare Workers + D1。

已定决策：

- 登录方式：用户名 + 密码，配一次性恢复码（无邮箱依赖）。
- API 域名：`https://pipa-api.alanvampuke.top`（Cloudflare 区域 `alanvampuke.top` 下的 Worker Route）。
- 保留游客模式：不登录也能练习并本地记账；登录后自动合并本机历史。

## 1. 数据与语义

活跃度的最小单位是「片段（segment）」，而不是每日总数。片段天然可加、可去重，多个设备各自产生的片段相加即为账号总量，不会因为覆盖写而丢数据。

- Android 现有本地记录键：`<wallNow>_<monotonic>:<segment>:<day>`，值 `source|day|elapsedMs`（SharedPreferences `practice_activity`）。**保持原样**，不改本地存储格式。
- 上传时片段 ID = `d:<deviceId>:<本地键>`；`deviceId` 为首次运行生成的随机 UUID（独立 SharedPreferences），因此跨设备、重装后都不会撞 ID。
- 已上传片段在本地记 `practice_sync` 的 `claimed:<片段ID>=<userId>`，同一片段不会重复上传，也不会被另一个账号再次认领。
- 服务端对同一 `(user_id, segment_id)` 幂等 upsert，`elapsed_ms = max(旧值, 新值)`，只增不减。

## 2. 服务端（`backend/`，Worker `pipa-api`）

D1 数据库 `pipa-activity`（UUID `e9afa50e-3cf1-41d0-b98f-b422d4399bd4`，APAC）。Schema 见 `backend/migrations/0001_init.sql`：

- `users`：`id`、`username`（NFKC→trim→lower，3–32，`[a-z0-9_]`，首字符字母）、`display_name`、`password_algorithm`、`password_salt`、`password_hash`、`password_iterations`、`pepper_version`、`auth_version`、`recovery_code_hash`、`created_at`、`updated_at`。
- `sessions`：`id`、`user_id`、`token_hash`（SHA-256(pepper+token)，唯一）、`device_name`、`device_id`、`created_at`、`last_used_at`、`expires_at`、`revoked_at`、`revoke_reason`。多行并存 = 多设备同时登录。
- `practice_segments`：`(user_id, segment_id)` 主键、`day`、`source`（basics/metronome/free）、`elapsed_ms`、`device_id`、`created_at`、`updated_at`；索引 `(user_id, day)`。
- `auth_attempts`：按 `scope`（`u:<username>` / `i:<ip>`）滑动窗口计数，用于失败锁定。
- `mutation_guards`：条件更新的 `changes()` 守卫。

密码：PBKDF2-HMAC-SHA256，WebCrypto，初始 10,000 次（免费档 10ms CPU 预算），存 `password_iterations` 以便日后在线升级；服务端 pepper 走 secret。密码长度 8–128，拒绝 Top-200 弱口令。未知用户名也必须跑一次 dummy PBKDF2，响应与密码错误完全一致（同状态码/错误码/文案/耗时量级）。

会话：token = 32 随机字节 base64url（43 字符）；`expires_at = now + 180d`（15,552,000 秒）。任何认证请求：若 `now - last_used_at >= 3600s`，则更新 `last_used_at=now`、`expires_at=now+180d`（滑动续期，最多每小时写一次）。过期/撤销返回 401 `session_invalid`。

限流：同用户名+IP 15 分钟内 5 次失败锁 15 分钟；同 IP 每小时 30 次注册；锁定期内即使密码正确也回 429 并带 `retry_after`。

接口（`/api/v1`，JSON，错误统一 `{"error":{"code","message"}}`）：

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/health` | 200 `{ok:true,apiVersion:1}` |
| POST | `/auth/register` | `{username,password,deviceName,deviceId}` → 201 `{token,expiresAt,user{id,username,displayName},recoveryCode}` |
| POST | `/auth/login` | 同上字段 → 200 同结构（无 recoveryCode） |
| GET | `/auth/me` | 认证 → `{user,expiresAt,session{id,createdAt,lastUsedAt}}` |
| POST | `/auth/logout` | 认证 → 204，撤销当前会话 |
| POST | `/auth/password` | `{currentPassword,newPassword}` → 200 `{token,expiresAt}`，撤销该用户全部旧会话并签发新会话 |
| POST | `/auth/recover` | `{username,recoveryCode,newPassword,deviceName,deviceId}` → 200 `{token,expiresAt,user,recoveryCode}`（新恢复码），撤销全部旧会话 |
| POST | `/auth/recovery-code` | 认证 + `{password}` → 200 `{recoveryCode}`，作废旧码 |
| POST | `/practice/segments` | 认证 → `{deviceId,segments:[{id,day,source,elapsedMs}]}`，≤500 条，整批校验，返回 `{accepted,serverTime}` |
| GET | `/practice/summary?from=&to=` | 认证 → `{days:[{day,basicsMs,metronomeMs,freeMs,totalMs}],totals{...},updatedAt}`，默认近 365 天 |

校验：`day` 为真实 `YYYY-MM-DD`，范围 `今天-400 天 … 今天+1 天`；`elapsedMs` 1…86,400,000；`source` 枚举；`id` 1…160 可打印 ASCII。全部按 `user_id` 隔离，越权一律 404。

D1 写入用 `env.DB.batch`，每批 ≤40 条语句。

## 3. Android 客户端

新增 `sync/`：

- `DeviceId`：首次运行生成 UUID 存 `practice_sync`。
- `TokenStore`：Android Keystore AES/GCM 加密落 `filesDir/account/session.bin`，AAD 绑 `com.vampuck.pipa_trainer:session:v1`；解密失败删文件回游客态，**不存明文 fallback**。
- `AuthApi`：`HttpURLConnection` + `org.json`，10s 连接/20s 读取超时；`Authorization: Bearer`。
- `SyncEngine`：单飞（Mutex）；401 → 清 token 并提示重新登录；网络错误保留 token 与队列，指数退避重试。
- `PracticeSyncer`：登录成功后先把本机所有未认领片段分批上传，再拉取汇总；此后每次练习结束、应用回到前台、手动点击时增量同步。
- `AccountStore`：用户名、userId、同步状态、上次同步时间、云端汇总缓存。

UI：

- `AccountActivity`（新）：游客态显示登录/注册表单（用户名、密码、设备名可编辑）、恢复码一次性展示并要求确认已保存；登录态显示 `@用户名`、在线设备数、上次同步时间、立即同步、修改密码、退出登录、重新生成恢复码。
- 首页活跃度卡片标题右侧显示账号状态：`本机记录` / `@name · 已同步` / `同步中`，点击进入账号页。
- 登录成功后若本机有未认领片段，提示「已将本机 N 天记录合并到账号」。
- 未登录、需要重新登录、同步尚未完成或本机存在待同步记录时展示本机数据；离线时保留当前账号已有的云端汇总，绝不出现空白或数据丢失。

展示口径：

- 当前账号有云端快照、没有待同步本机记录，且状态为 `OK` 或 `OFFLINE` → 展示云端汇总（含 basics/metronome/free 三源与每日明细）；快照不会因超过 15 分钟或跨日而失效，离线时也继续展示。
- 当前账号没有快照（包括未登录、登录失效或快照属于其他账号）、存在待同步本机记录，或状态为 `IDLE` / `SYNCING` / `AUTH_REQUIRED` → 展示本机记录。
- 两种状态都沿用现有 `PracticeStats.level/streak` 与「时长精确到秒」的格式。

## 4. 升级不丢数据

1. 本地 SharedPreferences 结构不变，升级后历史记录立即可见。
2. 首次登录把全部未认领片段上传；上传成功才写 `claimed`，失败下次重试。
3. 云端只增不删；服务端 upsert 取 max，重放安全。
4. 登录/注册失败、网络中断、令牌过期都不会清理本地记录。

## 5. 验证

服务端：vitest + `@cloudflare/vitest-pool-workers`，覆盖注册/登录/防枚举一致响应、180 天过期与滑动续期、多会话并存、logout 只撤销当前、改密与恢复撤销全部、恢复码单次竞争、片段幂等 upsert 与用户隔离、汇总三源相加、限流锁定、非法输入拒绝；迁移用 sqlite3 逐条验证约束。生产烟测：真实公网注册 → 上传 → 汇总 → 换 token 再拉 → 恢复码重置。

Android：纯 Kotlin 测试覆盖片段 ID 生成、未认领队列、上传分批、云端/本地回退决策、Keystore 失败回游客态；CI 跑单测 + debug/release 构建 + 签名校验。

报告分三档：CI 实测 / 生产公网实测 / 未验证（真机多设备、国内网络、Keystore 各机型差异）。
