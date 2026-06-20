# Blog Site

这是一个独立于现有 `docs/` 技术文档的静态博客目录，适合部署到 GitHub Pages。

## 目录

- `index.html`：博客首页
- `posts/`：文章页面
- `assets/styles.css`：公共样式

## 部署方式

仓库里已经附带了 GitHub Pages 工作流：

- 工作流文件：`.github/workflows/deploy-blog-pages.yml`
- 发布目录：`blog/`

如果你想把它部署成当前仓库的 GitHub Pages：

1. 把仓库推到 GitHub
2. 在仓库 `Settings -> Pages` 中把 Source 设为 `GitHub Actions`
3. 推送到默认分支后，工作流会自动发布 `blog/` 目录

如果你之后想迁移到专门的 `username.github.io` 仓库，也可以把整个 `blog/` 目录直接复制过去。
