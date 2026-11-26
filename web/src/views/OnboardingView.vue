<script setup lang="ts">
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { api } from '@/api'
import { messageForError } from '@/lib/errors'
import { useAuthStore } from '@/stores/auth'
import { useFamilyStore } from '@/stores/family'
import { useUiStore } from '@/stores/ui'
import Icon from '@/components/Icon.vue'
import LogoMark from '@/components/LogoMark.vue'

const auth = useAuthStore()
const family = useFamilyStore()
const ui = useUiStore()
const router = useRouter()

const familyName = ref('')
const inviteCode = ref('')
const creating = ref(false)
const joining = ref(false)
const createError = ref('')
const joinError = ref('')

async function complete(familyId: string): Promise<void> {
  auth.setFamilyId(familyId)
  family.reset()
  ui.toast('已加入家庭', 'success')
  await router.replace('/')
}

async function createFamily(): Promise<void> {
  createError.value = ''
  const name = familyName.value.trim()
  if (!name) {
    createError.value = '给家庭起一个名字吧。'
    return
  }
  creating.value = true
  try {
    const result = await api.families.create(name)
    await complete(result.family.id)
  } catch (err) {
    createError.value = messageForError(err, '创建失败，请稍后再试')
  } finally {
    creating.value = false
  }
}

async function joinFamily(): Promise<void> {
  joinError.value = ''
  const code = inviteCode.value.trim()
  if (!/^\d{6}$/.test(code)) {
    joinError.value = '请输入 6 位数字邀请码。'
    return
  }
  joining.value = true
  try {
    const result = await api.families.join(code)
    await complete(result.family.id)
  } catch (err) {
    joinError.value = messageForError(err, '加入失败，请检查邀请码')
  } finally {
    joining.value = false
  }
}

function logout(): void {
  auth.logout()
  void router.replace('/login')
}
</script>

<template>
  <div class="onboarding">
    <div class="onboarding__shape onboarding__shape--teal" aria-hidden="true" />
    <div class="onboarding__shape onboarding__shape--coral" aria-hidden="true" />

    <header class="onboarding__top">
      <LogoMark :size="32" wordmark micro />
      <button type="button" class="btn btn--ghost btn--sm" @click="logout">
        <Icon name="logout" :size="15" />
        退出登录
      </button>
    </header>

    <main class="onboarding__main">
      <div class="onboarding__intro">
        <p class="micro">STEP 02 · FAMILY</p>
        <h1 class="onboarding__title">先加入一个家庭</h1>
        <p class="onboarding__sub">
          {{ auth.user?.nickname ?? '你' }}，一个账号同一时间只能属于一个家庭。创建新的，或用邀请码加入家人的。
        </p>
      </div>

      <div class="onboarding__grid">
        <section class="choice-card">
          <div class="choice-card__art accent-teal">
            <Icon name="sparkle" :size="22" />
          </div>
          <p class="micro choice-card__eyebrow">CREATE</p>
          <h2 class="choice-card__title">创建家庭</h2>
          <p class="choice-card__copy">
            你将成为管理员。创建后会生成 6 位邀请码，家人凭码加入。
          </p>
          <form class="choice-card__form" novalidate @submit.prevent="createFamily">
            <div class="field">
              <label class="field__label" for="family-name">家庭名称</label>
              <input
                id="family-name"
                v-model="familyName"
                class="input"
                type="text"
                maxlength="30"
                placeholder="例如：我们的家"
                :disabled="creating || joining"
                @input="createError = ''"
              />
            </div>
            <p v-if="createError" class="field__error" role="alert">{{ createError }}</p>
            <button
              type="submit"
              class="btn btn--primary btn--block"
              :disabled="creating || joining"
            >
              <span v-if="creating" class="spinner" />
              {{ creating ? '正在创建…' : '创建家庭' }}
            </button>
          </form>
        </section>

        <section class="choice-card">
          <div class="choice-card__art accent-coral">
            <Icon name="key" :size="22" />
          </div>
          <p class="micro choice-card__eyebrow">JOIN</p>
          <h2 class="choice-card__title">加入家庭</h2>
          <p class="choice-card__copy">
            向家人要一个 6 位邀请码。管理员重置邀请码后，旧码会立即失效。
          </p>
          <form class="choice-card__form" novalidate @submit.prevent="joinFamily">
            <div class="field">
              <label class="field__label" for="join-code">邀请码</label>
              <input
                id="join-code"
                v-model="inviteCode"
                class="input input--code"
                type="text"
                inputmode="numeric"
                maxlength="6"
                placeholder="6 位数字"
                :disabled="creating || joining"
                @input="joinError = ''"
              />
            </div>
            <p v-if="joinError" class="field__error" role="alert">{{ joinError }}</p>
            <button
              type="submit"
              class="btn btn--block"
              :disabled="creating || joining"
            >
              <span v-if="joining" class="spinner" />
              {{ joining ? '正在加入…' : '加入家庭' }}
            </button>
          </form>
        </section>
      </div>

      <p class="onboarding__note">
        加入后，家庭成员之间可以互相查看位置。你可以随时在设置中暂停自己的共享。
      </p>
    </main>
  </div>
</template>

<style scoped>
.onboarding {
  position: relative;
  min-height: 100dvh;
  padding: 24px 40px 48px;
  overflow: hidden;
  background: var(--white);
}

.onboarding::before {
  content: '';
  position: absolute;
  inset: 0;
  background-image: radial-gradient(circle at 1px 1px, #eceef2 1px, transparent 1.1px);
  background-size: 30px 30px;
  opacity: 0.55;
  pointer-events: none;
}

.onboarding__shape {
  position: absolute;
  pointer-events: none;
}

.onboarding__shape--teal {
  top: -180px;
  right: -120px;
  width: 440px;
  height: 440px;
  border-radius: 50%;
  background: var(--teal);
  opacity: 0.6;
}

.onboarding__shape--coral {
  bottom: -260px;
  left: -160px;
  width: 400px;
  height: 400px;
  border-radius: 50%;
  background: var(--coral);
  opacity: 0.35;
}

.onboarding__top {
  position: relative;
  z-index: 2;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  max-width: 1040px;
  margin: 0 auto;
}

.onboarding__main {
  position: relative;
  z-index: 1;
  max-width: 1040px;
  margin: 64px auto 0;
}

.onboarding__intro {
  max-width: 620px;
}

.onboarding__title {
  margin-top: 12px;
  font-size: clamp(40px, 5vw, 56px);
  line-height: 1.08;
  letter-spacing: -0.032em;
  font-weight: 500;
}

.onboarding__sub {
  margin-top: 16px;
  color: var(--slate);
  font-size: 16.5px;
  line-height: 1.65;
  max-width: 540px;
}

.onboarding__grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 20px;
  margin-top: 36px;
}

.choice-card {
  display: flex;
  flex-direction: column;
  padding: 28px;
  border-radius: var(--r-panel);
  background: var(--white);
  box-shadow:
    0 0 0 1px var(--ring),
    0 20px 60px -50px rgba(28, 28, 30, 0.3);
}

.choice-card__art {
  display: grid;
  place-items: center;
  width: 48px;
  height: 48px;
  border-radius: 14px;
  margin-bottom: 22px;
}

.choice-card__eyebrow {
  font-size: 9px;
}

.choice-card__title {
  margin-top: 8px;
  font-size: 26px;
  line-height: 1.2;
  letter-spacing: -0.025em;
  font-weight: 500;
}

.choice-card__copy {
  margin-top: 8px;
  color: var(--slate);
  font-size: 13.5px;
  line-height: 1.6;
  min-height: 44px;
}

.choice-card__form {
  display: flex;
  flex-direction: column;
  gap: 12px;
  margin-top: 20px;
}

.input--code {
  letter-spacing: 0.24em;
  font-weight: 600;
  font-variant-numeric: tabular-nums;
}

.onboarding__note {
  margin-top: 26px;
  color: var(--ink-muted);
  font-size: 12px;
  line-height: 1.6;
}

@media (max-width: 900px) {
  .onboarding {
    padding: 20px 24px 40px;
  }

  .onboarding__main {
    margin-top: 40px;
  }

  .onboarding__grid {
    grid-template-columns: 1fr;
    gap: 16px;
  }

  .choice-card__copy {
    min-height: 0;
  }
}

@media (max-width: 600px) {
  .onboarding {
    padding: 16px 16px 32px;
  }

  .onboarding__main {
    margin-top: 30px;
  }

  .onboarding__title {
    font-size: 36px;
  }

  .onboarding__sub {
    font-size: 15px;
  }

  .choice-card {
    padding: 22px;
  }

}
</style>
