import { onBeforeUnmount, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { useMediaQuery } from './useMediaQuery'

const MOBILE_NAVIGATION_QUERY = '(max-width: 768px)'

export function useSidebarDrawer() {
  const route = useRoute()
  const sidebarOpen = ref(false)
  const isMobileNavigation = useMediaQuery(MOBILE_NAVIGATION_QUERY)

  function closeSidebar() {
    sidebarOpen.value = false
  }

  function toggleSidebar() {
    sidebarOpen.value = !sidebarOpen.value
  }

  watch(isMobileNavigation, (matches) => {
    if (!matches) closeSidebar()
  })

  watch(
    sidebarOpen,
    (open) => {
      document.body.classList.toggle('mobile-nav-open', open)
    },
    { immediate: true },
  )

  watch(
    () => route.fullPath,
    closeSidebar,
  )

  onBeforeUnmount(() => {
    closeSidebar()
    document.body.classList.remove('mobile-nav-open')
  })

  return {
    sidebarOpen,
    toggleSidebar,
    closeSidebar,
  }
}
