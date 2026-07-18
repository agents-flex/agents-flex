// https://vitepress.dev/guide/custom-theme
import { h } from 'vue'
import Theme from 'vitepress/theme'
import './style.css'
import MyLayout from "./MyLayout.vue";

export default {
  ...Theme,
  Layout: MyLayout,
  enhanceApp() {
    if (typeof window === 'undefined') return
    if (!window.matchMedia('(hover: hover) and (pointer: fine)').matches) return
    if (window.matchMedia('(prefers-reduced-motion: reduce)').matches) return

    let activeField: HTMLElement | null = null
    let latestEvent: PointerEvent | null = null
    let frame = 0

    const updateCursor = () => {
      frame = 0
      const event = latestEvent
      if (!event) return

      const target = event.target instanceof Element ? event.target : null
      const field = target?.closest<HTMLElement>('.af-stack-field') ?? null

      if (field !== activeField) {
        activeField?.classList.remove('is-cursor-active')
        activeField = field
      }

      if (!field) return
      const rect = field.getBoundingClientRect()
      field.style.setProperty('--cursor-x', `${event.clientX - rect.left}px`)
      field.style.setProperty('--cursor-y', `${event.clientY - rect.top}px`)
      field.classList.add('is-cursor-active')
    }

    document.addEventListener('pointermove', (event) => {
      latestEvent = event
      if (!frame) frame = window.requestAnimationFrame(updateCursor)
    }, { passive: true })

    document.addEventListener('pointerdown', (event) => {
      const target = event.target instanceof Element ? event.target : null
      const field = target?.closest<HTMLElement>('.af-stack-field')
      if (!field) return
      field.classList.add('is-cursor-clicking')
      window.setTimeout(() => field.classList.remove('is-cursor-clicking'), 160)
    }, { passive: true })
  }
}
