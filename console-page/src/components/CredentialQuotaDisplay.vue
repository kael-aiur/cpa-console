<script setup lang="ts">
import type { AccountQuota } from '@/types/quota'
import { formatResetTime } from '@/utils/quotaDisplay'

withDefaults(defineProps<{ quota?: AccountQuota; loading?: boolean; error?: string; showSummary?: boolean }>(), {
  showSummary: true,
})
defineEmits<{ retry: [] }>()

function percent(value: number): number {
  return Number.isFinite(value) ? Math.max(0, Math.min(100, value)) : 0
}
</script>

<template>
  <div class="credential-quota-display" aria-live="polite">
    <span v-if="loading" class="credential-quota-muted">额度加载中…</span>
    <div v-else-if="error" class="credential-quota-error" role="alert">
      <span>{{ error }}</span>
      <button type="button" class="table-action" @click="$emit('retry')">重试查询</button>
    </div>
    <template v-else-if="quota">
      <div v-if="showSummary" class="credential-quota-summary">
        <span>{{ quota.tierName }}</span>
        <span v-if="quota.activeResetCredits" :title="quota.activeResetCredits.credits.map(credit => `到期：${formatResetTime(credit.expiresAt)}`).join('\n')">
          可用重置 {{ quota.activeResetCredits.availableCount }} 次
        </span>
      </div>
      <div v-for="(window, index) in quota.windows" :key="`${window.label}-${index}`" class="quota-window">
        <div class="quota-window-head">
          <span>{{ window.label }}</span>
          <span class="quota-percent">剩余 {{ percent(window.remainingPercent) }}%</span>
        </div>
        <div class="quota-bar">
          <span class="quota-bar-fill" :class="{ low: percent(window.remainingPercent) < 30, medium: percent(window.remainingPercent) >= 30 && percent(window.remainingPercent) < 60 }" :style="{ width: `${percent(window.remainingPercent)}%` }"></span>
        </div>
        <small v-if="window.resetAt" class="credential-quota-muted">{{ formatResetTime(window.resetAt) }} 重置</small>
      </div>
      <span v-if="!quota.windows.length && quota.tierName !== '按量计费'" class="credential-quota-muted">暂无额度窗口</span>
    </template>
    <span v-else class="credential-quota-muted">暂无额度数据</span>
  </div>
</template>

<style scoped>
.credential-quota-display { min-width: 0; width: 100%; }
.credential-quota-summary { display: flex; flex-wrap: wrap; justify-content: space-between; gap: 6px; margin-bottom: 8px; font-size: 11px; }
.credential-quota-muted { color: var(--text-tertiary); font-size: 11px; overflow-wrap: anywhere; }
.credential-quota-error { display: flex; flex-wrap: wrap; align-items: center; gap: 6px; color: var(--danger-color, #dc3545); font-size: 12px; overflow-wrap: anywhere; }
.quota-window-head { flex-wrap: wrap; gap: 4px; }
.quota-window { margin-top: 8px; }
</style>
