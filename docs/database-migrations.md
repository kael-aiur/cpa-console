# 数据库平滑升级方案

本文档规定 CPA Console 发布后数据库结构变更和版本升级的标准方式。后续新增功能涉及数据库变更时，必须遵循本文档，不能直接修改已经发布或已经执行过的初始化 SQL。

## 目标

数据库升级应满足：

- 新版本可以在已有数据库上平滑执行；
- 已有业务数据不会因为升级丢失；
- 迁移失败时能够回滚或停止启动，不进入半可用状态；
- 所有环境按照相同顺序执行相同迁移；
- 迁移过程可追踪、可审计、可校验。

## 推荐方式

采用类似 `sub2api` 的版本化迁移方案：

- SQL 迁移文件按版本号排序；
- 迁移文件随应用一起发布；
- 数据库使用 `schema_migrations` 表记录已经执行的迁移；
- 每条迁移保存文件 SHA-256 checksum；
- 已执行迁移不能被修改或删除；
- 新结构变更只能新增迁移文件；
- 普通迁移使用事务；
- 应用启动阶段自动执行未完成迁移。

## 迁移文件规范

迁移文件建议放在：

```text
console-core/src/main/resources/db/migration/
```

命名格式：

```text
NNN__description.sql
```

示例：

```text
001__init.sql
002__add_usage_event_status.sql
003__add_usage_event_indexes.sql
```

规则：

1. 版本号必须递增；
2. 每个文件只实现一个清晰的逻辑变更；
3. 迁移文件一旦发布或执行，不得修改；
4. 不得删除已经发布的迁移文件；
5. 不得通过修改旧迁移文件来修复问题；
6. 新变更必须创建新的迁移文件；
7. 迁移 SQL 应尽量幂等，例如使用 `IF EXISTS` 或 `IF NOT EXISTS`。

### 正确示例

如果需要为 `usage_events` 增加字段：

```text
004__add_usage_event_response_status.sql
```

```sql
ALTER TABLE usage_events
ADD COLUMN response_status INTEGER NOT NULL DEFAULT 200;
```

不能直接修改 `001__init.sql`。

## 迁移记录表

迁移系统应维护：

```sql
CREATE TABLE IF NOT EXISTS schema_migrations (
    version TEXT PRIMARY KEY,
    description TEXT NOT NULL,
    checksum TEXT NOT NULL,
    installed_at TIMESTAMP NOT NULL,
    execution_time_ms INTEGER NOT NULL DEFAULT 0
);
```

字段含义：

- `version`：迁移版本，例如 `004`；
- `description`：迁移描述；
- `checksum`：迁移文件内容的 SHA-256；
- `installed_at`：迁移完成时间；
- `execution_time_ms`：迁移执行耗时。

## 启动时执行流程

```text
建立数据库连接
    ↓
创建 schema_migrations
    ↓
读取所有迁移文件
    ↓
按版本排序
    ↓
逐条检查迁移状态和 checksum
    ↓
执行未完成迁移
    ↓
记录迁移版本和 checksum
    ↓
初始化应用数据访问层
```

每条迁移的处理规则：

- 迁移不存在于 `schema_migrations`：在事务中执行并记录；
- 迁移已存在且 checksum 一致：跳过；
- 迁移已存在但 checksum 不一致：停止启动并报错；
- 迁移执行失败：回滚当前迁移，不写入完成记录；
- 不允许忽略 checksum 错误继续运行。

应用启动时迁移失败，应保持服务启动失败状态，避免代码和数据库结构不匹配。

## 现有数据库基线

当前项目已有通过旧版 `schema.sql` 初始化的数据库。迁移系统落地时需要先建立一次基线：

- 将当前完整结构整理为 `001__init.sql`；
- 新数据库执行 `001__init.sql`；
- 已存在数据库不能重复执行初始化 SQL；
- 对已存在数据库检查必要表、字段和索引；
- 结构完整时将 `001` 记录为已完成；
- 结构不完整时必须通过新的补偿迁移修复，不能直接伪造完成记录。

基线至少应检查：

- `users`；
- `credentials`；
- `usage_event_inbox`；
- `usage_events`；
- API key hash、时间字段、Token 字段和主要索引。

## SQLite 注意事项

项目当前使用 SQLite。对于 SQLite 支持良好的变更，可以直接在事务中执行：

- 新增表；
- 新增字段；
- 新增索引；
- 数据回填；
- 删除索引。

SQLite 对以下变更支持有限：

- 删除字段；
- 修改字段类型；
- 修改约束；
- 修改主键；
- 重建复杂索引或表结构。

此类变更应采用表重建流程：

```text
创建新表
    ↓
迁移旧数据
    ↓
校验数据
    ↓
删除旧表
    ↓
重命名新表
    ↓
重建索引和约束
```

涉及大量数据或表重建时，升级前必须备份数据库：

```bash
cp .data/cpa-console.db .data/cpa-console.db.backup-YYYYMMDDHHMMSS
```

## 并发与锁

CPA Console 主要以单机 SQLite 部署。迁移 runner 应保证同一数据库同一时刻只有一个进程执行迁移：

- 使用 SQLite 写事务获取数据库写锁；
- 配置合理的 busy timeout；
- 数据库被其他进程占用时等待或失败；
- 不允许多个实例同时修改 schema；
- 后续可增加 `.data/cpa-console.db.migrate.lock` 文件锁。

## 迁移文件不可变原则

如果已经执行过：

```text
002__add_usage_event_status.sql
```

后续发现 SQL 有问题，不能修改该文件。正确做法是新增：

```text
003__fix_usage_event_status.sql
```

这样可以保证：

- 开发、测试和生产环境结构一致；
- 数据库升级历史可审计；
- 灾备恢复可以重现完整结构；
- 不会因为旧 SQL 被修改造成环境分叉。

## 测试要求

每次新增数据库迁移至少应验证：

### 新数据库

- 空数据库可以执行完整迁移链；
- 所有表、字段和索引创建成功；
- `schema_migrations` 记录正确。

### 已有数据库升级

- 从旧版本数据库开始升级；
- 只执行未完成迁移；
- 已有数据保持不变；
- 新字段默认值、索引和约束正确。

### 重复启动

- 连续执行两次迁移 runner；
- 第二次不重复执行已完成迁移；
- 不产生重复数据或重复索引。

### checksum 校验

- 修改已执行迁移文件后启动失败；
- 新增迁移文件可以继续执行；
- 数据库记录的 checksum 与文件内容一致。

### 事务回滚

- 模拟迁移中途失败；
- 当前迁移的表结构和数据变化全部回滚；
- `schema_migrations` 不记录失败迁移；
- 之前已经完成的迁移不受影响。

## 发布升级流程

生产环境建议按以下顺序升级：

```text
停止旧版本服务
    ↓
备份 cpa-console.db
    ↓
替换新版本应用
    ↓
启动新版本
    ↓
启动阶段自动执行未完成迁移
    ↓
检查迁移日志和 schema_migrations
    ↓
验证核心功能
    ↓
开放服务
```

如果迁移失败：

1. 保持新版本服务停止；
2. 查看迁移错误；
3. 修复迁移脚本或恢复数据库备份；
4. 重新执行升级。

## 新功能开发要求

涉及数据库变更的新功能必须：

1. 先阅读本文档；
2. 创建新的版本化迁移文件；
3. 不修改历史迁移或初始化 SQL；
4. 为迁移增加测试；
5. 验证新数据库初始化和已有数据库升级；
6. 在提交说明中注明数据库变更；
7. 在发布说明中注明迁移版本和升级注意事项。

数据库迁移相关实现应集中在 `console-core`，Controller 和前端不得直接执行数据库结构变更。
