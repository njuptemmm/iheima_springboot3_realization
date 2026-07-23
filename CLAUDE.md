# iheima_springboot3_realization -- 项目约定

## Plan 管理

Claude Code 生成的 plan 文件（`ExitPlanMode` 审批的那份）默认落在 `~/.claude/plans/<auto-name>.md`。为了让 plan 与项目本身一起管理，本项目采用如下约定：

- **存储位置**：审批通过后，plan 文件迁移到 `d:\OneDrive\Desktop\internship\claude code\software\javaweb\` 下
- **命名规则**：kebab-case + 语义命名，例如 `redis-upgrade-plan-A.md`、`payment-refactor-plan.md`
- **迁移时机**：`ExitPlanMode` 被批准后立即迁移，同一会话内不遗留在默认目录
- **不覆盖**：新 plan 使用新文件名，历史 plan 不删除（作为学习和回顾资料）

Claude Code 每次进入本项目应遵守以上约定，无需用户重复提醒。
