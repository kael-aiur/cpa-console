import { onBeforeUnmount, ref } from 'vue'

export function useMediaQuery(query: string) {
  const mediaQueryList = window.matchMedia(query)
  const matches = ref(mediaQueryList.matches)

  function handleChange(event: MediaQueryListEvent) {
    matches.value = event.matches
  }

  mediaQueryList.addEventListener('change', handleChange)
  onBeforeUnmount(() => mediaQueryList.removeEventListener('change', handleChange))

  return matches
}
