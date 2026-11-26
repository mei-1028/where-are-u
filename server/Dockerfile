# Native deps (argon2) may need a toolchain on musl/alpine; Prisma needs openssl.
FROM node:24-alpine AS build-base
RUN sed -i 's/dl-cdn.alpinelinux.org/mirrors.aliyun.com/g' /etc/apk/repositories \
  && apk add --no-cache openssl libc6-compat python3 make g++
# The schema is copied only after npm ci; generate the client explicitly later.
ENV PRISMA_SKIP_POSTINSTALL_GENERATE=true
WORKDIR /app
COPY package.json package-lock.json ./
RUN npm ci

# Type-check/build and generate the Prisma client.
FROM build-base AS build
COPY tsconfig.json ./
COPY prisma ./prisma
COPY src ./src
ENV DATABASE_URL="file:./build.db"
RUN npx prisma generate \
  && npm run build

# Production-only dependencies, including the Prisma CLI used by db push on start.
FROM build-base AS prod-deps
COPY package.json package-lock.json ./
RUN npm ci --omit=dev
COPY prisma ./prisma
ENV DATABASE_URL="file:./build.db"
RUN npx prisma generate

FROM node:24-alpine AS runtime
RUN sed -i 's/dl-cdn.alpinelinux.org/mirrors.aliyun.com/g' /etc/apk/repositories \
  && apk add --no-cache openssl libc6-compat
ENV NODE_ENV=production \
    PORT=3000 \
    DATABASE_URL="file:/data/where-are-u.db" \
    TRUST_PROXY="loopback,uniquelocal"
WORKDIR /app

RUN mkdir -p /data

COPY --from=prod-deps /app/node_modules ./node_modules
COPY --from=build /app/dist ./dist
COPY --from=prod-deps /app/prisma ./prisma
COPY package.json ./

# The SQLite file and the schema directory must be writable by the app user.
RUN chown -R node:node /app/prisma /data

USER node
EXPOSE 3000

HEALTHCHECK --interval=30s --timeout=5s --start-period=15s --retries=3 \
  CMD wget -qO- http://127.0.0.1:3000/health >/dev/null || exit 1

# Apply the schema, then run the compiled server (no tsx in production).
CMD ["sh", "-c", "./node_modules/.bin/prisma db push --skip-generate && exec node dist/index.js"]
