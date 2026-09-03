<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref } from 'vue'
import { getAvailableModels } from '@/services/modelsApi'
import { streamAdminResponse } from '@/services/adminChatApi'
import type { AvailableModel } from '@/types/models'
import type { ChatMessage, ResponsesStreamEvent } from '@/services/adminChatApi'

const models = ref<AvailableModel[]>([])
const selectedModel = ref('')
const modelKeyword = ref('')
const modelMenuOpen = ref(false)
const messages = ref<ChatMessage[]>([])
const draft = ref('')
const loadingModels = ref(true)
const sending = ref(false)
const errorMessage = ref('')
const composer = ref<HTMLTextAreaElement | null>(null)
const messagesPanel = ref<HTMLElement | null>(null)
const modelSelect = ref<HTMLElement | null>(null)

const filteredModels = computed(() => {
  const keyword = modelKeyword.value.trim().toLowerCase()
  if (!keyword) return models.value
  return models.value.filter((model) => model.name.toLowerCase().includes(keyword) || model.tags.some((tag) => tag.toLowerCase().includes(keyword)))
})
const selectedModelLabel = computed(() => selectedModel.value || '选择一个模型')
const canSend = computed(() => Boolean(selectedModel.value && draft.value.trim() && !sending.value))

function chooseModel(model: AvailableModel) {
  selectedModel.value = model.name
  modelKeyword.value = ''
  modelMenuOpen.value = false
  void nextTick(() => composer.value?.focus())
}

function toggleModelMenu() {
  modelMenuOpen.value = !modelMenuOpen.value
  if (modelMenuOpen.value) void nextTick(() => document.querySelector<HTMLInputElement>('.model-experience-search')?.focus())
}

function handleOutsideClick(event: MouseEvent) {
  if (modelSelect.value && !modelSelect.value.contains(event.target as Node)) modelMenuOpen.value = false
}

function responseContent(content: string | Array<{ type?: string; text?: string }> | undefined): string {
  if (typeof content === 'string') return content
  return content?.map((part) => part.text ?? '').join('') ?? ''
}

async function sendMessage() {
  const content = draft.value.trim()
  if (!content || !selectedModel.value || sending.value) return
  messages.value.push({ role: 'user', content })
  const assistantIndex = messages.value.push({ role: 'assistant', content: '' }) - 1
  draft.value = ''
  sending.value = true
  errorMessage.value = ''
  await scrollToBottom()
  try {
    await streamAdminResponse(selectedModel.value, content, (eventType: string, event: ResponsesStreamEvent) => {
      if (eventType === 'response.output_text.delta' || event.type === 'response.output_text.delta') {
        messages.value[assistantIndex].content += event.delta ?? ''
        void scrollToBottom()
      }
      if (eventType === 'response.failed' || event.type === 'response.failed') {
        throw new Error(event.response?.error?.message || event.error?.message || '模型响应失败')
      }
    })
    if (!messages.value[assistantIndex].content) throw new Error('模型返回了空内容')
  } catch (error) {
    messages.value.splice(assistantIndex, 1)
    errorMessage.value = error instanceof Error ? error.message : '模型响应失败，请稍后重试'
  } finally {
    sending.value = false
    await scrollToBottom()
    void nextTick(() => composer.value?.focus())
  }
}

function handleComposerKeydown(event: KeyboardEvent) {
  if (event.key === 'Enter' && !event.shiftKey) {
    event.preventDefault()
    void sendMessage()
  }
}

async function scrollToBottom() {
  await nextTick()
  if (messagesPanel.value) messagesPanel.value.scrollTop = messagesPanel.value.scrollHeight
}

onMounted(async () => {
  document.addEventListener('click', handleOutsideClick)
  try {
    const response = await getAvailableModels()
    models.value = response.data.models
    selectedModel.value = models.value[0]?.name ?? ''
  } catch (error) {
    errorMessage.value = error instanceof Error ? error.message : '模型列表加载失败'
  } finally {
    loadingModels.value = false
  }
})

onBeforeUnmount(() => document.removeEventListener('click', handleOutsideClick))
</script>

<template>
  <section class="page admin-model-experience-page">
    <header class="admin-page-heading model-experience-heading">
      <div>
        <span class="page-eyebrow">ADMIN / PLAYGROUND</span>
        <h1>模型体验</h1>
        <p>选择模型进行临时对话，离开页面后对话内容不会保存。</p>
      </div>
    </header>

    <section class="model-experience-card">
      <header class="model-experience-toolbar">
        <div class="model-selector" ref="modelSelect">
          <span class="section-label">对话模型</span>
          <button type="button" class="model-selector-trigger" :disabled="loadingModels || models.length === 0" :aria-expanded="modelMenuOpen" @click.stop="toggleModelMenu">
            <span class="model-selector-value" :class="{ placeholder: !selectedModel }">{{ loadingModels ? '加载模型中…' : selectedModelLabel }}</span>
            <svg viewBox="0 0 24 24" aria-hidden="true"><path d="m6 9 6 6 6-6" /></svg>
          </button>
          <Transition name="menu">
            <div v-if="modelMenuOpen" class="model-selector-menu">
              <label class="model-experience-search">
                <svg viewBox="0 0 24 24" aria-hidden="true"><circle cx="11" cy="11" r="6.5" /><path d="m16 16 4.5 4.5" /></svg>
                <input v-model="modelKeyword" type="search" placeholder="输入关键词搜索模型" @click.stop />
              </label>
              <div class="model-option-list">
                <button v-for="model in filteredModels" :key="model.name" type="button" class="model-option" :class="{ selected: selectedModel === model.name }" @click="chooseModel(model)">
                  <span class="model-option-copy"><strong>{{ model.name }}</strong><small v-if="model.tags.length">{{ model.tags.join(' · ') }}</small></span>
                  <span v-if="selectedModel === model.name" class="model-option-check">✓</span>
                </button>
                <p v-if="filteredModels.length === 0" class="model-option-empty">没有找到匹配的模型</p>
              </div>
            </div>
          </Transition>
        </div>
        <span class="temporary-badge"><i></i>临时对话</span>
      </header>

      <div ref="messagesPanel" class="chat-messages" aria-live="polite">
        <div v-if="messages.length === 0" class="chat-empty-state">
          <div class="chat-empty-mark">✦</div>
          <h2>开始一段模型体验</h2>
          <p>在下方输入问题，消息仅保留在当前页面中。</p>
        </div>
        <article v-for="(message, index) in messages" :key="`${message.role}-${index}`" class="chat-message" :class="message.role">
          <div class="chat-message-label">{{ message.role === 'user' ? '你' : selectedModel }}</div>
          <div v-if="message.content" class="chat-bubble">{{ message.content }}</div>
          <div v-else-if="message.role === 'assistant' && sending" class="chat-bubble chat-thinking"><i></i><i></i><i></i></div>
        </article>
      </div>

      <footer class="chat-composer-area">
        <p v-if="errorMessage" class="form-error" role="alert">{{ errorMessage }}</p>
        <div class="chat-composer">
          <textarea ref="composer" v-model="draft" rows="1" :disabled="sending || !selectedModel" placeholder="输入消息，按 Enter 发送，Shift + Enter 换行" @keydown="handleComposerKeydown"></textarea>
          <button type="button" class="chat-send-button" :disabled="!canSend" aria-label="发送消息" @click="sendMessage"><svg viewBox="0 0 24 24" aria-hidden="true"><path d="M4 12 20 4l-3 16-5-6-8-2Z" /><path d="m12 14 5-5" /></svg></button>
        </div>
      </footer>
    </section>
  </section>
</template>
