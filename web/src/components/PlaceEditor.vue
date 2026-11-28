<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import type { PlaceInfo, PlaceType } from '@/api/types'
import { usePlacesStore } from '@/stores/places'
import { useUiStore } from '@/stores/ui'
import { messageForError } from '@/lib/errors'
import { reverseGeocode } from '@/lib/geocode'
import Icon from './Icon.vue'
import ModalSheet from './ModalSheet.vue'

const props = defineProps<{
  open: boolean
  /** Present when editing an existing place. */
  place?: PlaceInfo | null
  /** Present when creating from a map point. */
  draft?: { lat: number; lng: number } | null
  canManage?: boolean
}>()
const emit = defineEmits<{
  close: []
  saved: [place: PlaceInfo]
  deleted: [removedRules: number]
}>()

const places = usePlacesStore()
const ui = useUiStore()

const TYPES: Array<{ key: PlaceType; label: string }> = [
  { key: 'home', label: '家' },
  { key: 'school', label: '学校' },
  { key: 'company', label: '公司' },
  { key: 'other', label: '其他' },
]
const RADII = [100, 200, 500, 1000]

const name = ref('')
const type = ref<PlaceType>('other')
const radius = ref(200)
const address = ref('')
const resolving = ref(false)
const busy = ref(false)
const confirmingDelete = ref(false)
const error = ref('')

const isEdit = computed(() => Boolean(props.place))
const coordinates = computed(() => props.place ?? props.draft ?? null)
const canManage = computed(() => props.canManage !== false)

watch(
  () => props.open,
  (open) => {
    if (!open) {
      confirmingDelete.value = false
      error.value = ''
      return
    }
    name.value = props.place?.name ?? ''
    type.value = props.place?.type ?? 'other'
    radius.value = props.place?.radiusMeters ?? 200
    confirmingDelete.value = false
    error.value = ''
    address.value = ''
    const point = coordinates.value
    if (!point) return
    resolving.value = true
    void reverseGeocode(point.lat, point.lng)
      .then((label) => {
        address.value = label ?? ''
        if (!props.place && !name.value && label) {
          name.value = label.slice(0, 20)
        }
      })
      .finally(() => {
        resolving.value = false
      })
  },
  { immediate: true },
)

function typeLabel(value: PlaceType): string {
  return TYPES.find((item) => item.key === value)?.label ?? '其他'
}

function applyType(value: PlaceType): void {
  type.value = value
  if (!isEdit.value && (name.value === '' || TYPES.some((item) => item.label === name.value))) {
    name.value = value === 'other' ? '' : typeLabel(value)
  }
}

async function save(): Promise<void> {
  if (busy.value || !canManage.value) return
  const trimmed = name.value.trim()
  if (!trimmed) {
    error.value = '请填写地点名称'
    return
  }
  const point = coordinates.value
  if (!point) {
    error.value = '缺少坐标，请在地图上重新选点'
    return
  }
  busy.value = true
  error.value = ''
  try {
    const saved = props.place
      ? await places.update(props.place.id, {
          name: trimmed,
          type: type.value,
          radiusMeters: radius.value,
        })
      : await places.create({
          name: trimmed,
          type: type.value,
          lat: point.lat,
          lng: point.lng,
          radiusMeters: radius.value,
        })
    ui.toast(props.place ? '地点已更新' : '地点已添加', 'success')
    emit('saved', saved)
    emit('close')
  } catch (err) {
    error.value = messageForError(err, '保存失败，请稍后再试')
  } finally {
    busy.value = false
  }
}

async function remove(): Promise<void> {
  if (!props.place || busy.value || !canManage.value) return
  busy.value = true
  error.value = ''
  try {
    const removedRules = await places.remove(props.place.id)
    ui.toast(
      removedRules > 0
        ? `地点已删除，同时移除 ${removedRules} 条围栏提醒`
        : '地点已删除',
      'success',
    )
    emit('deleted', removedRules)
    emit('close')
  } catch (err) {
    error.value = messageForError(err, '删除失败，请稍后再试')
  } finally {
    busy.value = false
  }
}
</script>

<template>
  <ModalSheet
    :open="open"
    :title="isEdit ? `编辑地点 · ${place?.name ?? ''}` : '添加地点'"
    :description="isEdit ? '修改名称、类型或半径。' : '确认位置后给这个地点起个名字。'"
    @close="emit('close')"
  >
    <div class="place-form">
      <div class="place-form__hint">
        <Icon name="pin" :size="16" />
        <span>
          {{ resolving ? '正在解析地址…' : address || '已选中的地图位置' }}
        </span>
      </div>

      <label class="field">
        <span class="field__label">名称</span>
        <input
          v-model="name"
          class="input"
          maxlength="20"
          placeholder="例如：学校、外婆家"
          :disabled="!canManage"
        />
      </label>

      <div class="place-form__row">
        <span class="place-form__label">类型</span>
        <div class="chips">
          <button
            v-for="item in TYPES"
            :key="item.key"
            type="button"
            class="chip"
            :class="{ 'chip--blue': type === item.key }"
            :disabled="!canManage"
            @click="applyType(item.key)"
          >
            {{ item.label }}
          </button>
        </div>
      </div>

      <div class="place-form__row">
        <span class="place-form__label">半径</span>
        <div class="chips">
          <button
            v-for="value in RADII"
            :key="value"
            type="button"
            class="chip"
            :class="{ 'chip--blue': radius === value }"
            :disabled="!canManage"
            @click="radius = value"
          >
            {{ value }} 米
          </button>
        </div>
      </div>

      <p v-if="!canManage" class="place-form__note">
        只有地点的创建者或家庭管理员可以修改这个地点。
      </p>
      <p v-if="error" class="place-form__error" role="alert">{{ error }}</p>
    </div>

    <template #footer>
      <div class="place-footer">
        <div v-if="isEdit && canManage" class="place-delete">
          <template v-if="confirmingDelete">
            <span class="place-delete__hint">
              删除后，引用它的 {{ place?.usedByRules ?? 0 }} 条围栏提醒也会移除。
            </span>
            <button
              type="button"
              class="btn btn--danger btn--sm"
              :disabled="busy"
              @click="remove"
            >
              确认删除
            </button>
            <button
              type="button"
              class="btn btn--ghost btn--sm"
              @click="confirmingDelete = false"
            >
              再想想
            </button>
          </template>
          <button
            v-else
            type="button"
            class="place-delete__link"
            @click="confirmingDelete = true"
          >
            删除地点
          </button>
        </div>
        <button
          v-if="canManage"
          type="button"
          class="btn btn--primary btn--block"
          :disabled="busy"
          @click="save"
        >
          {{ busy ? '保存中…' : isEdit ? '保存修改' : '添加地点' }}
        </button>
        <button v-else type="button" class="btn btn--block" @click="emit('close')">
          关闭
        </button>
      </div>
    </template>
  </ModalSheet>
</template>

<style scoped>
.place-form {
  display: flex;
  flex-direction: column;
  gap: 14px;
}

.place-form__hint {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 10px 12px;
  border-radius: var(--r-card);
  background: var(--teal);
  color: var(--teal-ink);
  font-size: 12.5px;
  line-height: 1.5;
}

.field {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.field__label,
.place-form__label {
  font-size: 12px;
  font-weight: 600;
  color: var(--slate);
}

.place-form__row {
  display: flex;
  align-items: center;
  gap: 12px;
}

.place-form__label {
  flex: 0 0 40px;
}

.chips {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}

.chips .chip {
  border: 0;
  cursor: pointer;
}

.chips .chip:disabled {
  opacity: 0.55;
  cursor: default;
}

.place-form__note {
  font-size: 12px;
  color: var(--ink-muted);
  line-height: 1.6;
}

.place-form__error {
  font-size: 12.5px;
  color: var(--danger);
}

.place-footer {
  display: flex;
  flex-direction: column;
  gap: 10px;
  width: 100%;
}

.place-delete {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
}

.place-delete__hint {
  flex: 1 1 100%;
  font-size: 12px;
  color: var(--ink-muted);
}

.place-delete__link {
  font-size: 12px;
  color: var(--danger);
  background: none;
  border: 0;
  padding: 0;
  cursor: pointer;
}

.place-delete__link:hover {
  text-decoration: underline;
}
</style>
