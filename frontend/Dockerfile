# ============================================================
# Frontend Dockerfile (Multi-stage build)
# Stage 1: Build static assets using Node.js
# Stage 2: Serve optimized static assets with Nginx
# ============================================================

# Stage 1: Build
FROM node:18-alpine AS builder

WORKDIR /app

# Copy package definitions first for optimal layer caching
COPY package.json package-lock.json* ./

# Install dependencies cleanly
RUN npm ci || npm install

# Copy frontend source code
COPY . .

# Set production environment and build static bundle
ENV NODE_ENV=production
RUN npm run build

# Stage 2: Runtime
FROM nginx:1.25-alpine

# Remove default Nginx welcome page
RUN rm -rf /usr/share/nginx/html/*

# Copy built static assets from builder stage
COPY --from=builder /app/dist /usr/share/nginx/html

# Copy custom production Nginx configuration with reverse proxy & security headers
COPY nginx.conf /etc/nginx/conf.d/default.conf

# Expose HTTP port
EXPOSE 80

# Health check probe
HEALTHCHECK --interval=30s --timeout=5s --start-period=5s --retries=3 \
    CMD wget -q -O - http://127.0.0.1/ || exit 1

# Start Nginx in foreground
CMD ["nginx", "-g", "daemon off;"]
