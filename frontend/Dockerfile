FROM node:22-alpine AS build
WORKDIR /workspace
ARG VITE_FORDRING_API_BASE_URL=http://localhost:8080
ARG VITE_FORDRING_WS_BASE_URL=ws://localhost:8080
ARG VITE_FORDRING_OPERATOR_NAME=admin
ENV VITE_FORDRING_API_BASE_URL=$VITE_FORDRING_API_BASE_URL
ENV VITE_FORDRING_WS_BASE_URL=$VITE_FORDRING_WS_BASE_URL
ENV VITE_FORDRING_OPERATOR_NAME=$VITE_FORDRING_OPERATOR_NAME
COPY package*.json ./
RUN npm ci
COPY . .
RUN npm run build

FROM nginx:1.27-alpine
COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /workspace/dist /usr/share/nginx/html
EXPOSE 80

