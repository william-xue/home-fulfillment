# 工作台 UI 约定

- 沿用 [`workbench.css`](../frontend/src/workbench.css)：`flow` 排纵向内容，`surface` 划业务区，`filters` / `field` 排表单，`table-wrap` 容纳宽表，`status-chip` 显示业务状态。共享主题从根级颜色变量修改，不单页重定义。
- 列表参照 [`Purchases.vue`](../frontend/src/views/Purchases.vue)：左导航、标题、可提交筛选、表格、分页；点业务号进入详情。详情参照 [`Purchase.vue`](../frontend/src/views/Purchase.vue)，先身份与状态，再明细、作业和历史。
- 作业复用 Element Plus 主/次按钮、原生带 label 输入、提交态、行内反馈和原键重试。状态和按钮权限由服务端响应决定，页面隐藏不是授权。
