# Repository Guidelines

## Project Structure & Module Organization
This Maven multi-module repository contains:

- `console-core/`: shared domain/service Java code. Add sources under `src/main/java`, resources under `src/main/resources`, and tests under `src/test/java`.
- `console-server/`: Spring Boot entry point and deployable server. Its application class is `site.kael.cpa.console.Application`. Built frontend assets are emitted into `src/main/resources/public`.
- `console-page/`: Vue 3 + Vite + TypeScript frontend. Application source is under `src/`; do not edit generated files in `dist/` or the server’s `public/` assets directly.

## CPA API Integration Guide

This console integrates with the CLIProxyAPI (CPA) service. Before implementing or changing CPA API calls, read [`docs/cpa-api/README.md`](docs/cpa-api/README.md); it is the index for all module-specific API reference documents.

## Build, Test, and Development Commands
Run frontend commands from `console-page/` and Maven commands from the repository root.

- `npm run dev`: start the Vite development server.
- `npm run build:dev`: type-check and build local frontend output to `console-page/dist/`.
- `npm run build:spring`: type-check and emit production frontend assets to `console-server/src/main/resources/public/`.
- `mvn package -DskipTests`: build all Maven modules; automatically installs Node/npm dependencies, runs `build:spring`, and packages frontend assets into the server jar.
- `mvn test`: compile modules and run JUnit tests.

## Coding Style & Naming Conventions
- Java 21, four-space indentation, UTF-8 sources, and existing `site.kael.cpa.*` package structure.
- Use UpperCamelCase for classes, lowerCamelCase for members, and end executable statements with semicolons.
- TypeScript/Vue uses two-space indentation, `<script setup lang="ts">`, PascalCase component filenames, and the `@/*` alias for files under `console-page/src/`.
- Keep XML formatting in POMs consistent with existing modules.

## Testing Guidelines
No test suites are currently committed. For new backend code, add JUnit tests mirroring production packages (for example, `console-core/src/test/java/site/kael/cpa/...Test.java`) and run `mvn test`. Frontend tests should be placed near components or under `console-page/src/**/__tests__/`; update documentation if a framework such as Vitest is introduced.

## Commit & Pull Request Guidelines
No Git history is available yet. Use concise imperative commit subjects such as `Add console health endpoint` or `Update Vite build output`. Pull requests should include a summary, testing performed, linked issues, and screenshots for visible frontend changes. Ensure both `npm run build:spring` and `mvn package -DskipTests` pass before requesting review.

## Security & Configuration Tips
Do not commit `.env`, credentials, target artifacts, or generated public assets unless required for deployment. Proxy settings are intentionally handled by local/Maven configuration.

## Current Backend Implementation Goal

当前前端原型已基本完成，开发目标调整为实现后端服务，逐步支持前端功能。后端功能不要求一次性全部实现，必须按照用户提出的要求逐个功能推进；每次只实现当前明确要求的功能，并完成相应验证，避免提前扩展无关功能。

### Backend Module Layering

- `console-server/`：放置 `Controller` 层和 `Service` 层。
- `console-core/`：放置 `Manager` 层、`Dao` 层，以及所有与 CLIProxyAPI 对接的调用层。
- `Service` 层主要负责调用一个或多个 `Manager`，组合不同接口和数据，完成前端所需接口的业务编排、数据聚合和返回结构转换。
- `Manager` 层负责可复用的领域业务、数据处理和跨 Dao / CLIProxyAPI 调用的协调；`Dao` 层负责持久化数据访问；CLIProxyAPI 对接调用必须集中在 `console-core/`，不得直接放在 Controller 或 Service 中。
- 实现后端接口前，必须先阅读 [`docs/cpa-api/README.md`](docs/cpa-api/README.md) 及相关模块文档，确认 CLIProxyAPI 的接口和返回结构。

## Administrator Route Access Requirement

所有管理后台路由必须使用 `admin-` 前缀（例如 `#/admin-user`、`#/admin-credentials`）。所有路径以 `/admin-` 开头的页面都仅允许 `role === "admin"` 的用户访问；路由守卫必须校验当前登录用户角色。非管理员访问任何管理后台路由时，必须自动跳转回普通用户后台首页 `#/quota`；未登录用户则跳转到 `#/login`。后续新增管理后台页面或路由时，必须遵循并保持这一权限限制。

### Administrator API Path and Authorization Requirement

所有仅供管理员后台使用的后端 API 必须统一放在 `/admin/` 路径下（例如 `/admin/users`、`/admin/credentials`）。后端必须对 `/admin/` 路径进行统一权限拦截：先校验登录状态，再校验当前用户 `role === "admin"`；未登录请求必须拒绝，非管理员用户请求必须拒绝，不得仅依赖前端路由守卫或前端隐藏菜单来保证权限。新增管理员后台功能时，Controller 的接口路径、权限配置和相关测试都必须遵循此要求。
