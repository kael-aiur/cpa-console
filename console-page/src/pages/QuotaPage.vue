<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { getQuotaFiles, getQuotaForFile } from '@/services/quotaApi'
import type { AccountQuota, QuotaFile } from '@/types/quota'
import { getProviderIcon, getProviderLabel, getRecentStatus, getRecentSuccessRate } from '@/utils/quotaDisplay'

const files = ref<QuotaFile[]>([])
const loading = ref(true)
const activeTag = ref('all')
const refreshing = ref(false)
const quotas = ref<Record<string, AccountQuota | undefined>>({})
const quotaLoading = ref<Record<string, boolean>>({})

const tagTabs = computed(() => {
  const counts = new Map<string, number>()

  for (const file of files.value) {
    for (const tag of file.tags ?? []) {
      counts.set(tag, (counts.get(tag) ?? 0) + 1)
    }
  }

  return [
    { key: 'all', label: '全部', count: files.value.length },
    ...Array.from(counts.entries())
      .map(([key, count]) => ({ key, label: key, count }))
      .sort((a, b) => a.label.localeCompare(b.label, 'zh-Hans-CN')),
  ]
})

const filteredFiles = computed(() => {
  if (activeTag.value === 'all') return files.value
  return files.value.filter((file) => file.tags?.includes(activeTag.value))
})

const activeCount = computed(() => filteredFiles.value.filter((item) => item.status === 'active').length)
const errorCount = computed(() => filteredFiles.value.filter((item) => item.status === 'error').length)

async function loadFiles() {
  refreshing.value = true

  try {
    const response = await getQuotaFiles()
    files.value = response.data.files
    quotas.value = {}
    quotaLoading.value = Object.fromEntries(files.value.map((file) => [file.id, true]))
    for (let start = 0; start < files.value.length; start += 10) {
      const batch = files.value.slice(start, start + 10)
      await Promise.all(
        batch.map(async (file) => {
          try {
            const response = await getQuotaForFile(file)
            quotas.value[file.id] = response.quota
          } catch (error) {
            console.error(`Failed to load quota for ${file.name}`, error)
          } finally {
            quotaLoading.value[file.id] = false
          }
        }),
      )
    }
  } catch (error) {
    console.error('Failed to load quota files', error)
  } finally {
    loading.value = false
    refreshing.value = false
  }
}

function selectTag(tag: string) {
  activeTag.value = tag
}

function parseStatusMessage(message?: string): string {
  if (!message) return ''
  try {
    const parsed = JSON.parse(message) as { error?: { message?: string } }
    return parsed.error?.message ?? message
  } catch {
    return message
  }
}

function getQuota(file: QuotaFile): AccountQuota | undefined {
  return quotas.value[file.id]
}

function getBucketRequestCount(bucket: { success: number; failed: number }): number {
  return bucket.success + bucket.failed
}

function getBucketSuccessRate(bucket: { success: number; failed: number }): string {
  const total = getBucketRequestCount(bucket)
  if (total === 0) return '--'
  return `${((bucket.success / total) * 100).toFixed(1).replace('.0', '')}%`
}

function isQuotaLoading(file: QuotaFile): boolean {
  return quotaLoading.value[file.id] === true
}

function formatResetTime(value: string): string {
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? value : date.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' })
}

onMounted(() => {
  void loadFiles()
})
</script>

<template>
  <section class="quota-page">
    <header class="page-heading">
      <div class="heading-copy">
        <div class="page-title-line">
          <span class="page-eyebrow">QUOTA / OVERVIEW</span>
          <h1>额度查看</h1>
        </div>
        <p>
          <span>{{ files.length }} 个凭证</span>
          <span class="dot">·</span>
          <span class="healthy">{{ activeCount }} 正常</span>
          <template v-if="errorCount > 0">
            <span class="dot">·</span>
            <span class="attention">{{ errorCount }} 待处理</span>
          </template>
        </p>
      </div>

      <button
        type="button"
        class="refresh-action"
        :disabled="refreshing"
        @click="loadFiles()"
      >
        <svg :class="{ spinning: refreshing }" viewBox="0 0 24 24" aria-hidden="true">
          <path d="M21 12a9 9 0 1 1-9-9c2.52 0 4.93 1 6.74 2.74L21 8" />
          <path d="M21 3v5h-5" />
        </svg>
        刷新全部
      </button>
    </header>

    <div v-if="loading && files.length === 0" class="skeleton-grid" aria-hidden="true">
      <div v-for="item in 6" :key="item" class="quota-skeleton"></div>
    </div>

    <template v-else>
      <div class="tabs-row">
        <div class="provider-tabs" role="group" aria-label="标签筛选">
          <button
            v-for="tab in tagTabs"
            :key="tab.key"
            type="button"
            class="tag-tab"
            :class="{ active: activeTag === tab.key }"
            :aria-pressed="activeTag === tab.key"
            @click="selectTag(tab.key)"
          >
            <span v-if="tab.key === 'all'" class="tab-glyph">◎</span>
            <span class="tab-label">{{ tab.label }}</span>
            <span class="tab-count">{{ tab.count }}</span>
          </button>
        </div>
      </div>

      <TransitionGroup name="cards" tag="div" class="quota-grid">
        <article
          v-for="(file, index) in filteredFiles"
          :key="file.id"
          class="quota-card"
          :style="{ '--card-index': index }"
        >
          <header class="card-head">
            <span
              class="provider-icon-wrap"
              :class="{ codex: file.provider.toLowerCase() === 'codex' }"
              :title="getProviderLabel(file.provider)"
            >
              <img
                v-if="getProviderIcon(file.provider)"
                class="provider-icon"
                :src="getProviderIcon(file.provider)!"
                alt=""
              />
              <span v-else class="provider-fallback">
                {{ getProviderLabel(file.provider).slice(0, 1).toUpperCase() }}
              </span>
            </span>
            <div class="head-copy">
              <strong :title="file.name">{{ file.name }}</strong>
              <span class="account" :title="file.email">{{ file.email }}</span>
            </div>
            <span class="status-badge" :class="file.status === 'active' ? 'active' : 'error'">
              {{ file.status === 'active' ? '正常' : '异常' }}
            </span>
          </header>

          <div class="tag-list">
            <span v-for="tag in file.tags" :key="tag" class="file-tag">{{ tag }}</span>
          </div>

          <div v-if="isQuotaLoading(file)" class="quota-loading" aria-label="正在加载配额">
            <span class="quota-loading-line"></span>
            <span class="quota-loading-line short"></span>
          </div>
          <div v-else-if="getQuota(file)" class="quota-section">
            <div class="quota-section-head">
              <span>剩余配额</span>
              <span class="quota-tier">{{ getQuota(file)?.tierName }}</span>
            </div>
            <div v-for="window in getQuota(file)?.windows" :key="window.label" class="quota-window">
              <div class="quota-window-head">
                <span>{{ window.label }}</span>
                <span class="quota-percent">{{ window.remainingPercent }}% <small>· {{ formatResetTime(window.resetAt) }} 重置</small></span>
              </div>
              <div class="quota-bar">
                <span class="quota-bar-fill" :class="{ low: window.remainingPercent < 30, medium: window.remainingPercent >= 30 && window.remainingPercent < 60 }" :style="{ width: `${window.remainingPercent}%` }"></span>
              </div>
            </div>
          </div>

          <div class="stat-row">
            <div class="stat-item">
              <span>成功请求</span>
              <strong>{{ file.success.toLocaleString() }}</strong>
            </div>
            <div class="stat-item">
              <span>失败请求</span>
              <strong :class="{ attention: file.failed > 0 }">
                {{ file.failed.toLocaleString() }}
              </strong>
            </div>
          </div>

          <div class="recent-status">
            <div class="recent-status-head">
              <span>近期请求成功率</span>
              <strong
                class="recent-rate"
                :class="{
                  high: getRecentSuccessRate(file) !== null && getRecentSuccessRate(file)! >= 90,
                  medium: getRecentSuccessRate(file) !== null && getRecentSuccessRate(file)! >= 50 && getRecentSuccessRate(file)! < 90,
                  low: getRecentSuccessRate(file) !== null && getRecentSuccessRate(file)! < 50,
                }"
              >
                {{ getRecentSuccessRate(file) === null ? '--' : `${getRecentSuccessRate(file)!.toFixed(1).replace('.0', '')}%` }}
              </strong>
            </div>
            <div class="status-blocks" aria-label="最近 20 个请求时间段">
              <div
                v-for="bucket in file.recent_requests"
                :key="bucket.time"
                class="status-block-wrap"
              >
                <span class="status-block" :class="getRecentStatus(bucket.success, bucket.failed)"></span>
                <span class="status-tooltip" role="tooltip">
                  <strong>{{ bucket.time }}</strong>
                  <span>请求 {{ getBucketRequestCount(bucket) }} 次</span>
                  <span>成功率 {{ getBucketSuccessRate(bucket) }}</span>
                </span>
              </div>
            </div>
            <div class="recent-status-legend">
              <span><i class="legend-dot success"></i>成功</span>
              <span><i class="legend-dot failed"></i>失败</span>
              <span><i class="legend-dot idle"></i>无请求</span>
            </div>
          </div>

          <footer class="card-footer">
            <span v-if="file.id_token?.plan_type" class="plan-pill">
              {{ file.id_token.plan_type }}
            </span>
            <span class="last-refresh">
              {{ file.last_refresh ? `刷新 ${new Date(file.last_refresh).toLocaleString()}` : '未刷新' }}
            </span>
          </footer>

          <p v-if="parseStatusMessage(file.status_message)" class="status-message">
            {{ parseStatusMessage(file.status_message) }}
          </p>
        </article>
      </TransitionGroup>

      <div v-if="filteredFiles.length === 0" class="empty-state">
        <h2>该标签暂无凭证</h2>
        <p>请切换到其他标签，或返回「全部」查看。</p>
      </div>
    </template>
  </section>
</template>
