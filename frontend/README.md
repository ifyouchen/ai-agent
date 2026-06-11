# AI Agent Frontend

独立 Node.js 前端工程，使用 Vite 托管原有的 HTML/CSS/ES Module 页面。

## 本地开发

```bash
cd frontend
npm install
npm run dev
```

默认访问 `http://localhost:5173`，`/api` 和 `/actuator` 会代理到 `http://localhost:8080`。

如需修改后端地址：

```bash
cp .env.example .env
# 修改 VITE_BACKEND_TARGET
npm run dev
```

## 生产构建

```bash
cd frontend
npm run build
npm run preview
```

如果前端和后端部署在不同域名，构建时设置：

```bash
VITE_API_BASE_URL=http://localhost:8080 npm run build
```
