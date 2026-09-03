<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { getUsageRecords, getUsageSummary } from '@/services/usageApi'
import type { UsageRecord, UsageSummary, UsageTimeRange } from '@/types/usage'

type QuickRange = 'today' | 'yesterday' | 'week' | 'last7'

const MOCK_TODAY = new Date()
const pageSize = 10
const selectedQuick = ref<QuickRange | ''>('today')
const range = ref<UsageTimeRange>(getQuickRange('today'))
const customRange = ref({ start: toInputValue(range.value.start), end: toInputValue(range.value.end) })
const summary = ref<UsageSummary | null>(null)
const records = ref<UsageRecord[]>([])
const page = ref(1)
const total = ref(0)
const totalPages = ref(1)
const loading = ref(true)
const recordsLoading = ref(true)

function pad(value: number) { return String(value).padStart(2, '0') }
function formatDate(date: Date) { return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}` }
function toInputValue(value: string) {
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return ''
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}T${pad(date.getHours())}:${pad(date.getMinutes())}`
}
function getQuickRange(type: QuickRange): UsageTimeRange {
  const date = new Date(MOCK_TODAY)
  const start = new Date(date)
  const end = new Date(date)
  if (type === 'yesterday') { start.setDate(start.getDate() - 1); end.setDate(end.getDate() - 1) }
  if (type === 'week') { const day = start.getDay() || 7; start.setDate(start.getDate() - day + 1); end.setDate(start.getDate() + 6) }
  if (type === 'last7') start.setDate(start.getDate() - 6)
  start.setHours(0, 0, 0, 0)
  end.setHours(23, 59, 0, 0)
  return { start: start.toISOString(), end: end.toISOString() }
}
function selectQuick(type: QuickRange) {
  selectedQuick.value = type
  range.value = getQuickRange(type)
  customRange.value = { start: toInputValue(range.value.start), end: toInputValue(range.value.end) }
  page.value = 1
}
function applyCustomRange() {
  if (!customRange.value.start || !customRange.value.end) return
  selectedQuick.value = ''
  range.value = { start: new Date(customRange.value.start).toISOString(), end: new Date(customRange.value.end).toISOString() }
  page.value = 1
}
function formatRange(value: string) { return new Date(value).toLocaleString('zh-CN', { hour12: false }) }
function formatNumber(value: number) { return value.toLocaleString('zh-CN') }
function formatTokenNumber(value: number) {
  const absolute = Math.abs(value)
  if (absolute >= 1_000_000_000) return `${(value / 1_000_000_000).toFixed(2).replace(/\.00$/, '').replace(/(\.\d)0$/, '$1')}b`
  if (absolute >= 1_000_000) return `${(value / 1_000_000).toFixed(2).replace(/\.00$/, '').replace(/(\.\d)0$/, '$1')}m`
  return formatNumber(value)
}
function formatDuration(value: number) { return value >= 1000 ? `${(value / 1000).toFixed(2)} s` : `${value} ms` }
function formatTime(value: string) { return new Date(value).toLocaleString('zh-CN', { hour12: false }) }
function percent(value: number, totalValue: number) { return totalValue ? (value / totalValue) * 100 : 0 }
function modelGradient(data: Record<string, number> | undefined) {
  if (!data) return 'conic-gradient(#dedbd3 0 100%)'
  const colors = ['#2d2a26', '#39bd92', '#d89c16', '#6d80d8']
  let offset = 0
  const sections = Object.entries(data).map(([_, value], index) => { const start = offset; offset += percent(value, Object.values(data).reduce((a, b) => a + b, 0)); return `${colors[index % colors.length]} ${start}% ${offset}%` })
  return `conic-gradient(${sections.join(', ')})`
}
async function load() {
  loading.value = true; recordsLoading.value = true
  const [summaryResponse, recordsResponse] = await Promise.all([getUsageSummary(range.value), getUsageRecords(range.value, page.value, pageSize)])
  summary.value = summaryResponse.data
  records.value = recordsResponse.data.records
  total.value = recordsResponse.data.total
  totalPages.value = recordsResponse.data.total_pages
  loading.value = false; recordsLoading.value = false
}
async function changePage(next: number) { page.value = next; const response = await getUsageRecords(range.value, page.value, pageSize); records.value = response.data.records }
watch(range, () => void load(), { deep: true })
onMounted(() => void load())

const tokenTotal = computed(() => Object.values(summary.value?.model_token_distribution ?? {}).reduce((a, b) => a + b, 0))
const requestTotal = computed(() => Object.values(summary.value?.model_request_distribution ?? {}).reduce((a, b) => a + b, 0))
const modelColors = ['#2d2a26', '#39bd92', '#d89c16', '#6d80d8']
</script>

<template>
  <section class="page usage-page">
    <header class="usage-heading"><div><div class="page-title-line"><span class="page-eyebrow">USAGE / ANALYTICS</span><h1>用量查看</h1></div></div><button type="button" class="refresh-action" :disabled="loading" @click="load">刷新数据</button></header>

    <section class="usage-filter-card">
      <div class="usage-filter-header"><span class="section-label">时间范围</span><span class="selected-range">{{ formatRange(range.start) }} — {{ formatRange(range.end) }}</span></div>
      <div class="quick-ranges"><button v-for="item in [{ key: 'today', label: '今天' }, { key: 'yesterday', label: '昨天' }, { key: 'week', label: '本周' }, { key: 'last7', label: '过去 7 天' }]" :key="item.key" type="button" :class="{ active: selectedQuick === item.key }" @click="selectQuick(item.key as QuickRange)">{{ item.label }}</button></div>
      <div class="custom-range"><label>开始时间<input v-model="customRange.start" type="datetime-local" /></label><span>至</span><label>结束时间<input v-model="customRange.end" type="datetime-local" /></label><button type="button" class="filter-apply" @click="applyCustomRange">应用</button></div>
    </section>

    <div v-if="loading" class="usage-loading"><span v-for="item in 3" :key="item"></span></div>
    <template v-else>
      <section class="usage-stat-grid"><article><span>总请求数</span><strong>{{ formatNumber(summary?.total_requests ?? 0) }}</strong><small>次请求</small></article><article><span>总 Token 数</span><strong>{{ formatTokenNumber(summary?.total_tokens ?? 0) }}</strong><small>输入 + 输出 Token</small></article><article><span>平均耗时</span><strong>{{ formatDuration(summary?.average_duration_ms ?? 0) }}</strong><small>每次请求平均响应时间</small></article></section>

      <section class="usage-chart-grid">
        <article class="usage-chart-card"><header><div><span class="section-label">模型 Token 占比</span><p>按总 Token 数计算</p></div></header><div class="donut-layout"><div class="donut-chart" :style="{ background: modelGradient(summary?.model_token_distribution) }"><div><strong>{{ formatTokenNumber(summary?.total_tokens ?? 0) }}</strong><span>Token</span></div></div><div class="chart-legend"><div v-for="(value, model, index) in summary?.model_token_distribution" :key="model"><i :style="{ background: modelColors[index % modelColors.length] }"></i><span>{{ model }}</span><strong>{{ percent(value, tokenTotal).toFixed(1) }}%</strong></div></div></div></article>
        <article class="usage-chart-card"><header><div><span class="section-label">模型请求占比</span><p>按请求次数计算</p></div></header><div class="donut-layout"><div class="donut-chart" :style="{ background: modelGradient(summary?.model_request_distribution) }"><div><strong>{{ formatNumber(summary?.total_requests ?? 0) }}</strong><span>Requests</span></div></div><div class="chart-legend"><div v-for="(value, model, index) in summary?.model_request_distribution" :key="model"><i :style="{ background: modelColors[index % modelColors.length] }"></i><span>{{ model }}</span><strong>{{ percent(value, requestTotal).toFixed(1) }}%</strong></div></div></div></article>
      </section>

      <section class="usage-record-card"><header class="usage-record-header"><div><span class="section-label">请求记录</span><p>按请求时间倒序排列，共 {{ total }} 条记录</p></div></header><div v-if="recordsLoading" class="table-loading"></div><div v-else class="usage-table-wrap"><table class="usage-table"><thead><tr><th>请求模型</th><th>输入 Token</th><th>输出 Token</th><th>缓存命中 Token</th><th>请求时间</th><th>请求耗时</th><th>响应码</th><th>是否成功</th></tr></thead><tbody><tr v-for="record in records" :key="record.id"><td><strong>{{ record.model }}</strong></td><td>{{ formatTokenNumber(record.input_tokens) }}</td><td>{{ formatTokenNumber(record.output_tokens) }}</td><td>{{ formatTokenNumber(record.cached_tokens) }}</td><td>{{ formatTime(record.request_time) }}</td><td>{{ formatDuration(record.duration_ms) }}</td><td><span class="response-code" :class="{ error: record.status_code >= 400 }">{{ record.status_code }}</span></td><td><span class="success-state" :class="{ failed: !record.success }"><i></i>{{ record.success ? '成功' : '失败' }}</span></td></tr></tbody></table></div><footer class="usage-pagination"><span>第 {{ page }} / {{ totalPages }} 页</span><button type="button" :disabled="page <= 1" @click="changePage(page - 1)">上一页</button><button type="button" :disabled="page >= totalPages" @click="changePage(page + 1)">下一页</button></footer></section>
    </template>
  </section>
</template>
