import iconAntigravity from '@/assets/icons/antigravity.svg'
import iconCodex from '@/assets/icons/codex.svg'
import iconClaude from '@/assets/icons/claude.svg'
import iconGrok from '@/assets/icons/grok.svg'
import iconGemini from '@/assets/icons/gemini.svg'
import iconVertex from '@/assets/icons/vertex.svg'
import iconKimi from '@/assets/icons/kimi.svg'
import iconOpenAI from '@/assets/icons/openai.svg'
import iconZhipu from '@/assets/icons/zhipu.svg'
import iconOpenRouter from '@/assets/icons/openrouter.svg'

const providerIcons: Record<string, string> = {
  antigravity: iconAntigravity,
  codex: iconCodex,
  anthropic: iconClaude,
  claude: iconClaude,
  grok: iconGrok,
  gemini: iconGemini,
  'gemini-interactions': iconGemini,
  interactions: iconGemini,
  'interactions-api-key': iconGemini,
  'google interactions': iconGemini,
  'gemini interactions': iconGemini,
  vertex: iconVertex,
  kimi: iconKimi,
  moonshot: iconKimi,
  openai: iconOpenAI,
  zhipu: iconZhipu,
  openrouter: iconOpenRouter,
}

export function getProviderIcon(provider: string): string | null {
  return providerIcons[provider?.toLowerCase?.() ?? ''] ?? null
}

export function getProviderLabel(provider: string): string {
  const normalized = provider?.toLowerCase?.() ?? ''
  if (normalized === 'antigravity') return 'Antigravity'
  if (normalized === 'codex') return 'Codex'
  if (normalized === 'anthropic' || normalized === 'claude') return 'Anthropic'
  if (normalized === 'grok') return 'Grok'
  if (normalized === 'gemini') return 'Gemini'
  if (normalized === 'gemini-interactions' || normalized === 'interactions' || normalized === 'interactions-api-key' || normalized === 'google interactions' || normalized === 'gemini interactions') return 'Google Interactions'
  if (normalized === 'vertex') return 'Vertex'
  if (normalized === 'kimi' || normalized === 'moonshot') return 'Kimi'
  if (normalized === 'openai') return 'OpenAI'
  if (normalized === 'zhipu') return '智谱 GLM'
  if (normalized === 'openrouter') return 'OpenRouter'
  return provider || 'Unknown'
}

export type RecentStatus = 'idle' | 'success' | 'failure' | 'mixed'

export function getRecentStatus(success: number, failed: number): RecentStatus {
  if (success + failed === 0) return 'idle'
  if (failed === 0) return 'success'
  if (success === 0) return 'failure'
  return 'mixed'
}

export function getRecentSuccessRate(file: { success: number; failed: number }): number | null {
  const total = file.success + file.failed
  return total > 0 ? (file.success / total) * 100 : null
}

export function formatResetTime(value: string): string {
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return value

  // Some providers only return a clock time, while others include the exact
  // reset date. Keep the compact display for the former and show both parts
  // whenever the upstream value contains a calendar date.
  const hasCalendarDate = /(?:^|\D)\d{4}[-/]\d{1,2}[-/]\d{1,2}(?:\D|$)/.test(value)
  return hasCalendarDate
    ? date.toLocaleString([], {
        year: 'numeric',
        month: '2-digit',
        day: '2-digit',
        hour: '2-digit',
        minute: '2-digit',
      })
    : date.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' })
}
