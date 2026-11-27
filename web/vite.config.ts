import { fileURLToPath, URL } from 'node:url'
import vue from '@vitejs/plugin-vue'
import { defineConfig, loadEnv } from 'vite'

export default defineConfig(({ command, mode }) => {
  const fileEnv = loadEnv(mode, process.cwd(), '')
  const useMock =
    String(process.env.VITE_USE_MOCK ?? fileEnv.VITE_USE_MOCK ?? '')
      .trim()
      .toLowerCase() === 'true'

  if (command === 'build' && useMock) {
    throw new Error(
      'Refusing production build: VITE_USE_MOCK=true. Mock data is for local development and tests only.',
    )
  }

  // Optional dev-only proxy. Point the browser at the same-origin "/api" path
  // and let Vite forward to a remote server, which avoids CORS and mixed
  // content entirely (handy when the server only allows its own origin).
  const devApiTarget = String(
    process.env.VITE_DEV_API_TARGET ?? fileEnv.VITE_DEV_API_TARGET ?? '',
  ).trim()

  return {
    plugins: [vue()],
    resolve: {
      alias: {
        '@': fileURLToPath(new URL('./src', import.meta.url)),
        // In a clean build this resolves to a tiny stub, so neither seed data
        // nor the demo credentials can end up in the production bundle.
        '@mock-runtime': useMock
          ? fileURLToPath(new URL('./src/api/mock.ts', import.meta.url))
          : fileURLToPath(new URL('./src/api/mock-disabled.ts', import.meta.url)),
      },
    },
    server: {
      port: 5173,
      host: true,
      proxy: devApiTarget
        ? {
            '/api': {
              target: devApiTarget,
              changeOrigin: true,
              secure: false,
            },
          }
        : undefined,
    },
  }
})
