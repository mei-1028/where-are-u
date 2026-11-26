<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { RouterLink, useRoute, useRouter } from 'vue-router'
import { isMockMode } from '@/api'
import { ApiError } from '@/api/errors'
import { messageForError } from '@/lib/errors'
import { getApiBase, setApiBaseOverride } from '@/lib/session'
import { useAuthStore } from '@/stores/auth'
import { useFamilyStore } from '@/stores/family'
import { useUiStore } from '@/stores/ui'
import AuthLayout from '@/components/AuthLayout.vue'
import Icon from '@/components/Icon.vue'

const auth = useAuthStore()
const family = useFamilyStore()
const ui = useUiStore()
const route = useRoute()
const router = useRouter()

const username = ref('')
const password = ref('')
const showPassword = ref(false)
const submitting = ref(false)
const error = ref('')
const advanced = ref(false)
const serverAddress = ref(getApiBase())
const serverHint = ref('')
const serverError = ref('')

interface DemoInfo {
  username: string
  password: string
  familyName: string
  memberCount: number
}

const demo = ref<DemoInfo | null>(null)

onMounted(async () => {
  if (isMockMode) {
    const { mockDemoInfo } = await import('@mock-runtime')
    demo.value = mockDemoInfo
    if (demo.value) {
      username.value = demo.value.username
      password.value = demo.value.password
    }
  }
  if (route.query.reason === 'expired') {
    error.value = '登录已过期，请重新登录。'
  }
})

function safeRedirect(): string {
  const redirect = route.query.redirect
  if (typeof redirect === 'string' && redirect.startsWith('/') && !redirect.startsWith('//')) {
    return redirect
  }
  return '/'
}

async function submit(): Promise<void> {
  if (submitting.value) return
  error.value = ''
  serverHint.value = ''
  serverError.value = ''
  if (!username.value.trim() || !password.value) {
    error.value = '请输入用户名和密码。'
    return
  }
  if (advanced.value) {
    const validation = setApiBaseOverride(serverAddress.value)
    if (!validation.ok) {
      serverError.value = validation.error
      error.value = validation.error
      return
    }
    serverAddress.value = validation.value
  }
  submitting.value = true
  try {
    await auth.login({
      username: username.value.trim(),
      password: password.value,
    })
    family.reset()
    if (auth.familyId) {
      await router.replace(safeRedirect())
      ui.toast('欢迎回来', 'success')
    } else {
      await router.replace('/onboarding')
    }
  } catch (err) {
    error.value =
      err instanceof ApiError && err.code === 'UNAUTHORIZED'
        ? '用户名或密码不正确'
        : messageForError(err, '登录失败，请稍后再试')
  } finally {
    submitting.value = false
  }
}

async function demoLogin(): Promise<void> {
  if (!demo.value) return
  username.value = demo.value.username
  password.value = demo.value.password
  await submit()
}

function saveServer(): void {
  serverHint.value = ''
  const validation = setApiBaseOverride(serverAddress.value)
  if (!validation.ok) {
    serverError.value = validation.error
    return
  }
  serverError.value = ''
  serverAddress.value = validation.value
  serverHint.value = '服务器地址已保存，将在后续请求中使用；退出登录时清除。'
}
</script>

<template>
  <AuthLayout
    eyebrow="WELCOME BACK · 登录"
    title="欢迎回来"
    subtitle="登录后，看看家人此刻在哪。位置只对家庭成员可见。"
  >
    <form class="auth-form" novalidate @submit.prevent="submit">
      <div v-if="error" class="alert alert--error" role="alert">
        <Icon name="alert" :size="16" />
        <span>{{ error }}</span>
      </div>

      <div class="field">
        <label class="field__label" for="login-username">用户名</label>
        <input
          id="login-username"
          v-model="username"
          class="input"
          type="text"
          autocomplete="username"
          placeholder="请输入用户名"
          :disabled="submitting"
        />
      </div>

      <div class="field">
        <label class="field__label" for="login-password">密码</label>
        <div class="input-affix">
          <input
            id="login-password"
            v-model="password"
            class="input"
            :type="showPassword ? 'text' : 'password'"
            autocomplete="current-password"
            placeholder="至少 8 位"
            :disabled="submitting"
          />
          <button
            type="button"
            class="input-affix__button"
            :aria-label="showPassword ? '隐藏密码' : '显示密码'"
            @click="showPassword = !showPassword"
          >
            <Icon :name="showPassword ? 'eye-off' : 'eye'" :size="17" />
          </button>
        </div>
      </div>

      <button
        type="button"
        class="advanced-toggle"
        :aria-expanded="advanced"
        @click="advanced = !advanced"
      >
        <Icon name="settings" :size="13" />
        服务器地址（高级）
        <Icon
          name="chevron-down"
          :size="14"
          class="advanced-toggle__chevron"
          :class="{ 'is-open': advanced }"
        />
      </button>

      <div v-if="advanced" class="advanced-panel">
        <label class="field__label" for="server-address">API Base URL</label>
        <input
          id="server-address"
          v-model="serverAddress"
          class="input"
          :class="{ 'input--invalid': serverError }"
          type="url"
          placeholder="https://your-domain/api"
          spellcheck="false"
          :aria-invalid="Boolean(serverError)"
          @input="serverError = ''"
        />
        <p class="field__hint">
          仅支持 HTTPS；开发环境下可使用 localhost 或内网 HTTP。保存的地址会在退出登录时清除。
        </p>
        <button type="button" class="link-btn" @click="saveServer">保存地址</button>
        <p v-if="serverError" class="field__error" role="alert">{{ serverError }}</p>
        <p v-else-if="serverHint" class="field__hint">{{ serverHint }}</p>
      </div>

      <button type="submit" class="btn btn--primary btn--lg btn--block" :disabled="submitting">
        <span v-if="submitting" class="spinner" />
        {{ submitting ? '正在登录…' : '登录' }}
      </button>
    </form>

    <div v-if="demo" class="demo-card">
      <div class="demo-card__head">
        <span class="chip chip--teal">演示模式</span>
        <span class="micro">SEEDED DATA</span>
      </div>
      <p class="demo-card__copy">
        无需服务器。演示账号 <code>{{ demo.username }}</code> /
        <code>{{ demo.password }}</code>，内置{{ demo.familyName }}
        {{ demo.memberCount }} 位成员。
      </p>
      <button type="button" class="btn btn--sm" :disabled="submitting" @click="demoLogin">
        一键体验
        <Icon name="arrow-right" :size="14" />
      </button>
    </div>

    <p class="auth-alt">
      还没有账号？
      <RouterLink to="/register">注册一个新账号</RouterLink>
    </p>
  </AuthLayout>
</template>

<style scoped>
.auth-form {
  display: flex;
  flex-direction: column;
  gap: 16px;
  margin-top: 26px;
}

.advanced-toggle {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  align-self: flex-start;
  padding: 2px 0;
  color: var(--ink-muted);
  font-size: 12px;
  font-weight: 500;
  transition: color 0.15s;
}

.advanced-toggle:hover {
  color: var(--ink);
}

.advanced-toggle__chevron {
  transition: transform 0.18s var(--ease-out);
}

.advanced-toggle__chevron.is-open {
  transform: rotate(180deg);
}

.advanced-panel {
  display: flex;
  flex-direction: column;
  gap: 8px;
  padding: 14px;
  border-radius: 10px;
  background: var(--surface-soft);
  border: 1px solid var(--line);
}

.advanced-panel .input {
  min-height: 42px;
  font-size: 14px;
  background: #fff;
}

.demo-card {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: 9px;
  margin-top: 20px;
  padding: 14px 16px;
  border-radius: 12px;
  background: #f4fdfb;
  box-shadow: inset 0 0 0 1px rgba(195, 250, 245, 0.9);
}

.demo-card__head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  width: 100%;
  gap: 12px;
}

.demo-card__head .micro {
  font-size: 8.5px;
  color: #7fa7a3;
}

.demo-card__copy {
  color: var(--slate);
  font-size: 12.5px;
  line-height: 1.6;
}

.demo-card__copy code {
  padding: 1px 4px;
  border-radius: 4px;
  background: rgba(195, 250, 245, 0.75);
  color: var(--teal-ink);
  font-family: 'SFMono-Regular', 'Cascadia Code', Consolas, monospace;
  font-size: 11.5px;
}

.demo-card .btn {
  min-height: 30px;
  font-size: 12px;
  padding: 0 10px;
}

.auth-alt {
  margin-top: 22px;
  padding-top: 20px;
  border-top: 1px solid var(--line);
  color: var(--ink-muted);
  font-size: 13px;
  text-align: center;
}

.auth-alt a {
  color: var(--blue-pressed);
  font-weight: 600;
  text-decoration: underline;
  text-decoration-color: transparent;
  text-underline-offset: 3px;
  transition: text-decoration-color 0.15s;
}

.auth-alt a:hover {
  text-decoration-color: var(--blue);
}
</style>
