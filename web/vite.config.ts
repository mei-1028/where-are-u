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
    },
  }
})
