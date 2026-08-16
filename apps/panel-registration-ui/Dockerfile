# Build the Vite React app, then serve static files with nginx.
FROM node:22-alpine AS build
WORKDIR /app

COPY package.json package-lock.json ./
RUN npm ci

COPY . .

# Browser calls MES from this URL (baked in at build time).
# Default = laptop API. Override for K3s staging, e.g.:
#   docker build --build-arg VITE_MES_API_BASE_URL=http://<node-ip>:30100 .
ARG VITE_MES_API_BASE_URL=http://localhost:8080
ENV VITE_MES_API_BASE_URL=$VITE_MES_API_BASE_URL

RUN npm run build

FROM nginx:alpine
COPY --from=build /app/dist /usr/share/nginx/html
EXPOSE 80
CMD ["nginx", "-g", "daemon off;"]
