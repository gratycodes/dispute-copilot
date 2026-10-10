# Build context: repository root (the console bundles policy/dispute-policy.md).
FROM node:22-slim AS build
WORKDIR /src/console
COPY console/package.json console/package-lock.json ./
RUN npm ci --no-audit --no-fund
COPY policy /src/policy
COPY console ./
RUN npx tsc && npx vite build

FROM nginx:1.29-alpine
COPY console/nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /src/console/dist /usr/share/nginx/html
EXPOSE 80
