# 01 — 后台管理员角色与可信搜索范围

**What to build:** 后台认证身份可拥有 `ORDER_OPERATOR`、`FINANCE_VIEWER`、`INVENTORY_VIEWER` 三种角色；网关向下游传递可信角色声明；订单后台检索可从已验证身份构造不可由请求参数扩张的搜索范围。

**Blocked by:** None — can start immediately.

**Status:** completed

- [x] 无后台角色、缺失角色或伪造角色声明的请求不能访问订单后台检索。
- [x] 三种后台角色均能由已验证身份可靠解析，且搜索范围不接受请求参数或未验证 Header 的扩张。
- [x] 相关认证与角色传递行为具备模块级验证（2026-09-15：JDK 17 下三个模块全新编译及 15 项现有测试通过，临时副本补充的 7 项授权探针 HTTP 验收测试通过，共 22 项；未复现 Lombok 阻断）。
