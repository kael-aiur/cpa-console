# CPA Console

CPA Console 是一个面向 [CLIProxyAPI](https://github.com/router-for-me/CLIProxyAPI) 的 Web 管理控制台，用于统一管理 CPA 服务相关配置、用户、凭证、额度和请求用量。

项目采用前后端分离的模块化设计：后端使用 Java 21 + Spring Boot，前端使用 Vue 3 + Vite + TypeScript，数据使用 SQLite 本地持久化。

## 当前功能

### 用户登录

- 使用 CLIProxyAPI API Key 登录控制台。
- 登录后通过 Session 保持登录状态。
- 支持退出登录。
- API Key 在本地数据库中使用 HMAC Hash 做匹配，并使用 AES-GCM 加密保存。

### 普通用户后台

#### 额度查看

- 查看 CPA 管理端提供的认证文件和凭证。
- 显示凭证类型、供应商、状态、标签和近期请求状态。
- 支持查看不同凭证的额度信息。
- 支持 Gemini、Claude、Codex、Kimi、Antigravity、xAI、Vertex 等供应商相关额度数据。

#### 用量查看

- 查看当前用户 API Key 对应的请求用量。
- 支持快捷时间范围：
  - 今天
  - 昨天
  - 本周
  - 过去 7 天
- 支持自定义开始时间和结束时间。
- 查看总请求数、总 Token 数和平均耗时。
- 查看模型 Token 占比和模型请求占比。
- 查看请求记录，包括：
  - 请求模型
  - 输入 Token
  - 输出 Token
  - 缓存命中 Token
  - 请求时间
  - 请求耗时
  - 响应状态
  - 成功或失败状态
- Token 数量支持简洁格式显示，例如 `1.84m`、`1.54b`。

### 管理员后台

所有管理员页面使用 `/admin-` 路由前缀，所有管理员 API 使用 `/admin/` 路径，并由后端统一校验管理员权限。

#### 用户管理

- 查看控制台用户列表。
- 查看用户角色和脱敏 API Key。
- 创建用户并自动从 CPA 生成 API Key。
- 修改用户昵称和角色。
- 删除用户。
- 通过授权接口复制用户 API Key。

#### 凭证管理

- 同步并查看 CPA 当前凭证。
- 查看凭证类型、状态、引用 ID 和标签。
- 编辑凭证标签。

#### 用量统计

- 查看全部用户的请求和 Token 统计。
- 可按指定用户筛选统计数据。
- 支持不筛选用户，查看全部用户数据。
- 查看：
  - 模型 Token 占比
  - 模型请求占比
  - 用户 Token 占比
  - 用户请求占比
- 请求记录中显示请求用户、请求模型、Token、耗时和成功状态。

## 用量数据采集

控制台后台会持续从 CPA 获取 usage event，并分为采集和处理两个阶段：

```text
CPA usage event
      ↓
采集来源
      ↓
usage_event_inbox 临时事件表
      ↓
解析和归一化
      ↓
usage_events 结构化事件表
      ↓
用量查询和统计
```

支持两种采集模式，可通过配置切换：

### HTTP 模式

默认模式，调用 CPA 管理接口：

```http
GET /v0/management/usage-queue?count=...
```

适合 CPA 只通过 Nginx HTTPS 443 对外暴露的部署环境，不需要额外开放 Redis 端口。

### Redis 模式

使用 Redis usage channel 和队列：

- 正常情况下使用 `SUBSCRIBE usage` 实时接收事件；
- 订阅失败或连接断开后使用 Redis `LPOP` 轮询补偿；
- 订阅恢复时先执行队列 backfill；
- 兼容 CPA 新版 `usage` 和旧版 `queue` 队列 key。

## 数据存储

项目默认使用 SQLite，数据库文件位于：

```text
.data/cpa-console.db
```

主要数据表：

- `users`：控制台用户和 API Key 信息。
- `credentials`：CPA 凭证同步数据。
- `usage_event_inbox`：用量事件临时表，保存待处理原始 JSON。
- `usage_events`：解析后的结构化请求事件。

结构化事件表保存 API Key 的 `api_key_hash`，不保存 API Key 明文或密文。个人用量查询通过当前用户的 `users.api_key_hash` 与事件表匹配。

临时事件表为了支持原始事件解析，短时间内会保存 CPA 原始 JSON；事件处理成功后会删除对应临时记录。

## 项目结构

```text
cpa-console/
├── console-core/                  # 核心领域、DAO、Manager、CPA 对接层
│   ├── src/main/java/
│   ├── src/main/resources/schema.sql
│   └── src/test/java/
├── console-server/                # Spring Boot 服务、Controller、Service
│   ├── src/main/java/
│   └── src/main/resources/
├── console-page/                  # Vue 3 + Vite + TypeScript 前端
│   ├── src/pages/
│   ├── src/services/
│   ├── src/types/
│   └── src/router/
├── docs/cpa-api/                  # CLIProxyAPI 管理接口参考文档
├── pom.xml
└── README.md
```

模块职责：

- `console-server/`：Controller 和 Service 层，负责 HTTP 接口和业务编排。
- `console-core/`：Manager、DAO、领域模型，以及所有 CLIProxyAPI 对接逻辑。
- `console-page/`：前端页面、路由、API 服务和展示逻辑。

## 环境要求

- Java 21
- Maven 3.9+（或可用的 Maven 环境）
- Node.js 22+
- npm 11+
- CLIProxyAPI 服务

## 配置

默认配置位于：

```text
console-server/src/main/resources/application.yml
```

### CPA 配置

```yaml
cpa:
  base-url: ${CPA_BASE_URL:http://127.0.0.1:8317}
  timeout-ms: ${CPA_TIMEOUT_MS:5000}
  management-key: ${CPA_MANAGEMENT_KEY:}
  usage-mode: ${CPA_USAGE_MODE:http}
  usage-fallback-interval: ${CPA_USAGE_FALLBACK_INTERVAL:1m}
  usage-process-interval: ${CPA_USAGE_PROCESS_INTERVAL:1s}
  usage-batch-size: ${CPA_USAGE_BATCH_SIZE:1000}
  usage-process-batch-size: ${CPA_USAGE_PROCESS_BATCH_SIZE:100}
```

### HTTP 模式配置

如果 CPA 通过 HTTPS 443 提供管理接口：

```bash
CPA_BASE_URL=https://cpa.example.com
CPA_MANAGEMENT_KEY=your-management-key
CPA_USAGE_MODE=http
```

### Redis 模式配置

```bash
CPA_BASE_URL=http://127.0.0.1:8317
CPA_MANAGEMENT_KEY=your-management-key
CPA_USAGE_MODE=redis
CPA_REDIS_ADDR=127.0.0.1:8317
CPA_REDIS_TLS=false
```

Redis 地址未配置时，程序会根据 `CPA_BASE_URL` 推导地址；但如果 CPA 只通过普通 HTTPS 反向代理暴露 443 端口，该端口不能直接作为 Redis RESP 地址使用。

### 控制台配置

```yaml
console:
  data-dir: ${CPA_CONSOLE_DATA_DIR:.data}
  api-key-encryption-key: ${CPA_CONSOLE_API_KEY_ENCRYPTION_KEY:}
```

`CPA_CONSOLE_API_KEY_ENCRYPTION_KEY` 必须是 Base64 编码的 AES 密钥，长度为 16、24 或 32 字节。例如可以使用 32 字节随机密钥：

```bash
CPA_CONSOLE_API_KEY_ENCRYPTION_KEY="$(openssl rand -base64 32)"
```

生产环境必须妥善保存该密钥。密钥变更后，历史加密 API Key 将无法解密。

## 开发运行

### 启动前端开发服务器

```bash
cd console-page
npm install
npm run dev
```

前端开发服务器会将以下请求代理到本地后端：

```text
/api   -> http://127.0.0.1:8080
/admin -> http://127.0.0.1:8080
```

### 构建前端

开发构建：

```bash
cd console-page
npm run build:dev
```

构建前端并输出到 Spring Boot 静态资源目录：

```bash
cd console-page
npm run build:spring
```

### 构建后端

在项目根目录执行：

```bash
mvn package -DskipTests
```

构建过程会自动安装前端依赖、构建前端资源并打包 Maven 模块。

### 运行测试

```bash
mvn test
```

前端类型检查：

```bash
cd console-page
npm run type-check
```

## 主要 API

### 普通用户用量

```http
GET /api/usage/summary?start={ISO-8601}&end={ISO-8601}
GET /api/usage/records?start={ISO-8601}&end={ISO-8601}&page=1&page_size=10
```

普通用户接口只返回当前登录用户 API Key 对应的数据。

### 管理员用量

```http
GET /admin/usage/users
GET /admin/usage/summary?start={ISO-8601}&end={ISO-8601}
GET /admin/usage/summary?start={ISO-8601}&end={ISO-8601}&user_id=1
GET /admin/usage/records?start={ISO-8601}&end={ISO-8601}&page=1&page_size=10
GET /admin/usage/records?start={ISO-8601}&end={ISO-8601}&user_id=1&page=1&page_size=10
```

管理员用量接口需要管理员登录状态。未登录或非管理员请求会被后端拒绝。

## 安全说明

- 不要提交 `.env`、数据库文件、日志、私钥、证书或真实凭据。
- 不要在日志中输出 CPA management key、用户 API Key 或临时事件原始 JSON。
- 管理员 API 由后端校验权限，不能只依赖前端路由守卫。
- API Key 明文只在必要的登录和授权复制流程中使用。
- `.data/`、`target/`、前端构建产物和常见密钥文件已加入 `.gitignore`。
- 生产环境建议使用 HTTPS、VPN 或 SSH 隧道访问 CPA 管理服务和 Redis。

## 当前限制

- 当前费用字段已预留 Token 和模型信息，但费用计算接口和费用报表尚未实现。
- 请求事件暂时根据 `failed` 字段推断响应码：成功显示 200，失败显示 500。
- HTTP usage-queue 模式依赖 CPA usage queue 的保留时间，采集服务长期停止期间可能无法补回已经过期的事件。
- 当前前端没有独立的自动刷新策略，需要手动刷新页面或点击刷新按钮。

## License

当前项目许可证信息待补充。
