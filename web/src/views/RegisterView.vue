<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { RouterLink, useRoute, useRouter } from 'vue-router'
import { messageForError } from '@/lib/errors'
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
const nickname = ref('')
const password = ref('')
const confirmPassword = ref('')
const inviteCode = ref('')
const submitting = ref(false)
const error = ref('')
const fieldErrors = ref<Record<string, string>>({})

onMounted(() => {
  const invite = route.query.invite
  if (typeof invite === 'string' && /^\d{6}$/.test(invite)) inviteCode.value = invite
})

function validate(): boolean {
  const errors: Record<string, string> = {}
  const name = username.value.trim()
  if (name.length < 3 || name.length > 20) {
    errors.username = '用户名需要 3–20 位。'
  } else if (/\s/.test(name)) {
    errors.username = '用户名不能包含空格。'
  }
  const nick = nickname.value.trim()
  if (!nick || nick.length > 20) {
    errors.nickname = '昵称需要 1–20 个字符。'
  }
  if (password.value.length < 8) {
    errors.password = '密码至少 8 位。'
  }
  if (confirmPassword.value !== password.value) {
    errors.confirmPassword = '两次输入的密码不一致。'
  }
  if (inviteCode.value.trim() && !/^\d{6}$/.test(inviteCode.value.trim())) {
    errors.inviteCode = '邀请码是 6 位数字。'
  }
  fieldErrors.value = errors
  return Object.keys(errors).length === 0
}

async function submit(): Promise<void> {
  if (submitting.value) return
  error.value = ''
  if (!validate()) return
  submitting.value = true
  try {
    const result = await auth.register({
      username: username.value.trim(),
      nickname: nickname.value.trim(),
      password: password.value,
      inviteCode: inviteCode.value.trim() || undefined,
    })
    family.reset()
    ui.toast('账号已创建', 'success')
    if (result.familyId) await router.replace('/')
    else await router.replace('/onboarding')
  } catch (err) {
    error.value = messageForError(err, '注册失败，请稍后再试')
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <AuthLayout
    eyebrow="CREATE ACCOUNT · 注册"
    title="创建账号"
    subtitle="建立你自己的家庭，或用家人的邀请码直接加入。"
    wide
  >
    <form class="auth-form" novalidate @submit.prevent="submit">
      <div v-if="error" class="alert alert--error" role="alert">
        <Icon name="alert" :size="16" />
        <span>{{ error }}</span>
      </div>

      <div class="form-grid form-grid--auth">
        <div class="field">
          <label class="field__label" for="register-username">用户名</label>
          <input
            id="register-username"
            v-model="username"
            class="input"
            :class="{ 'input--invalid': fieldErrors.username }"
            type="text"
            autocomplete="username"
            placeholder="3–20 位"
            :disabled="submitting"
            @input="fieldErrors.username = ''"
          />
          <p v-if="fieldErrors.username" class="field__error">{{ fieldErrors.username }}</p>
        </div>

        <div class="field">
          <label class="field__label" for="register-nickname">昵称</label>
          <input
            id="register-nickname"
            v-model="nickname"
            class="input"
            :class="{ 'input--invalid': fieldErrors.nickname }"
            type="text"
            autocomplete="nickname"
            placeholder="家人看到的名字"
            :disabled="submitting"
            @input="fieldErrors.nickname = ''"
          />
          <p v-if="fieldErrors.nickname" class="field__error">{{ fieldErrors.nickname }}</p>
        </div>

        <div class="field">
          <label class="field__label" for="register-password">密码</label>
          <input
            id="register-password"
            v-model="password"
            class="input"
            :class="{ 'input--invalid': fieldErrors.password }"
            type="password"
            autocomplete="new-password"
            placeholder="至少 8 位"
            :disabled="submitting"
            @input="fieldErrors.password = ''"
          />
          <p v-if="fieldErrors.password" class="field__error">{{ fieldErrors.password }}</p>
        </div>

        <div class="field">
          <label class="field__label" for="register-confirm">确认密码</label>
          <input
            id="register-confirm"
            v-model="confirmPassword"
            class="input"
            :class="{ 'input--invalid': fieldErrors.confirmPassword }"
            type="password"
            autocomplete="new-password"
            placeholder="再输入一次"
            :disabled="submitting"
            @input="fieldErrors.confirmPassword = ''"
          />
          <p v-if="fieldErrors.confirmPassword" class="field__error">
            {{ fieldErrors.confirmPassword }}
          </p>
        </div>

        <div class="field span-2">
          <label class="field__label" for="register-invite">
            邀请码 <span class="field__optional">选填</span>
          </label>
          <input
            id="register-invite"
            v-model="inviteCode"
            class="input input--code"
            :class="{ 'input--invalid': fieldErrors.inviteCode }"
            type="text"
            inputmode="numeric"
            maxlength="6"
            autocomplete="off"
            placeholder="6 位数字"
            :disabled="submitting"
            @input="fieldErrors.inviteCode = ''"
          />
          <p v-if="fieldErrors.inviteCode" class="field__error">{{ fieldErrors.inviteCode }}</p>
          <p v-else class="field__hint">
            填了邀请码会直接加入家人的家庭；不填则注册后创建或加入。
          </p>
        </div>
      </div>

      <button type="submit" class="btn btn--primary btn--lg btn--block" :disabled="submitting">
        <span v-if="submitting" class="spinner" />
        {{ submitting ? '正在创建…' : '注册并继续' }}
      </button>

    </form>

    <p class="auth-alt">
      已经有账号了？
      <RouterLink to="/login">直接登录</RouterLink>
    </p>
  </AuthLayout>
</template>

<style scoped>
.auth-form {
  display: flex;
  flex-direction: column;
  gap: 18px;
  margin-top: 26px;
}

.form-grid--auth {
  gap: 16px;
}

.field__optional {
  margin-left: 4px;
  color: var(--ink-muted);
  font-weight: 400;
  letter-spacing: 0;
  text-transform: none;
}

.input--code {
  letter-spacing: 0.22em;
  font-variant-numeric: tabular-nums;
  font-weight: 600;
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

@media (max-width: 600px) {
  .form-grid--auth {
    grid-template-columns: 1fr;
  }

}
</style>
