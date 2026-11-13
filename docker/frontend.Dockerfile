# 前端镜像：Node 构建 Vue3 产物，Nginx 托管静态文件并反向代理 /api 到 Web 节点。
FROM node:22-alpine AS build

WORKDIR /frontend
COPY frontend/package.json frontend/package-lock.json ./
RUN npm ci
COPY frontend/index.html frontend/vite.config.js ./
COPY frontend/public ./public
COPY frontend/src ./src
RUN npm run build

FROM nginx:1.27-alpine

ENV TZ=Asia/Shanghai
COPY docker/nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /frontend/dist /usr/share/nginx/html

EXPOSE 80
