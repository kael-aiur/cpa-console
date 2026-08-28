<script setup lang="ts">
defineProps<{
  title: string
}>()
</script>

<template>
  <section class="card">
    <h1>{{ title }}</h1>
    <p>Vue 3 + Vite 前端工程已初始化。</p>
  </section>
</template>
