<script setup lang="ts">
import {onBeforeUnmount, onMounted} from 'vue'

const ENHANCED_ATTRIBUTE = 'data-mermaid-enhanced'
const SVG_NAMESPACE = 'http://www.w3.org/2000/svg'

let observer: MutationObserver | undefined
let fallbackFullscreen: HTMLElement | undefined

function createIconButton(label: string, icon: string): HTMLButtonElement {
  const button = document.createElement('button')
  button.type = 'button'
  button.className = 'mermaid-action'
  button.setAttribute('aria-label', label)
  button.title = label
  button.innerHTML = icon
  return button
}

function getDiagramSource(diagram: HTMLElement): string | undefined {
  const encoded = diagram.dataset.mermaidSource
  if (!encoded) return undefined

  try {
    return decodeURIComponent(encoded)
  } catch {
    return undefined
  }
}

async function copyText(text: string) {
  if (navigator.clipboard?.writeText) {
    await navigator.clipboard.writeText(text)
    return
  }

  const textarea = document.createElement('textarea')
  textarea.value = text
  textarea.style.position = 'fixed'
  textarea.style.opacity = '0'
  document.body.appendChild(textarea)
  textarea.select()
  document.execCommand('copy')
  textarea.remove()
}

function setSourceMode(
  wrapper: HTMLElement,
  diagram: HTMLElement,
  sourceView: HTMLElement,
  button: HTMLButtonElement,
  enabled: boolean,
) {
  wrapper.classList.toggle('is-source-mode', enabled)
  diagram.hidden = enabled
  sourceView.hidden = !enabled
  button.setAttribute('aria-pressed', String(enabled))
  button.setAttribute('aria-label', enabled ? '查看流程图' : '查看源码')
  button.title = enabled ? '查看流程图' : '查看源码'
}

function setFullscreenLabel(button: HTMLButtonElement, expanded: boolean) {
  const label = expanded ? '退出全屏' : '全屏查看'
  button.setAttribute('aria-label', label)
  button.setAttribute('aria-pressed', String(expanded))
  button.title = label
  button.dataset.expanded = String(expanded)
}

async function toggleFullscreen(wrapper: HTMLElement, button: HTMLButtonElement) {
  if (document.fullscreenElement === wrapper) {
    await document.exitFullscreen()
    return
  }

  if (document.fullscreenElement) await document.exitFullscreen()

  if (wrapper.requestFullscreen) {
    await wrapper.requestFullscreen()
    return
  }

  fallbackFullscreen?.classList.remove('is-fallback-fullscreen')
  fallbackFullscreen = wrapper
  wrapper.classList.add('is-fallback-fullscreen')
  document.documentElement.classList.add('has-mermaid-fullscreen')
  setFullscreenLabel(button, true)
}

function closeFallbackFullscreen() {
  if (!fallbackFullscreen) return
  fallbackFullscreen.classList.remove('is-fallback-fullscreen')
  const button = fallbackFullscreen.querySelector<HTMLButtonElement>('.mermaid-action--fullscreen')
  if (button) setFullscreenLabel(button, false)
  document.documentElement.classList.remove('has-mermaid-fullscreen')
  fallbackFullscreen = undefined
}

function getDownloadName(diagram: HTMLElement, extension = 'png'): string {
  const diagrams = Array.from(document.querySelectorAll<HTMLElement>('.vp-doc .mermaid'))
  const index = diagrams.indexOf(diagram) + 1
  const pageName = document.title.split('|')[0].trim() || 'mermaid'
  const safeName = pageName.replace(/[\\/:*?"<>|]+/g, '-').replace(/\s+/g, '-')
  return `${safeName}-diagram-${Math.max(index, 1)}.${extension}`
}

function downloadBlob(blob: Blob, filename: string) {
  const downloadUrl = URL.createObjectURL(blob)
  const link = document.createElement('a')
  link.href = downloadUrl
  link.download = filename
  link.click()
  window.setTimeout(() => URL.revokeObjectURL(downloadUrl), 1000)
}

function downloadSvg(svg: SVGSVGElement, diagram: HTMLElement) {
  const clone = svg.cloneNode(true) as SVGSVGElement
  clone.setAttribute('xmlns', SVG_NAMESPACE)
  const source = new XMLSerializer().serializeToString(clone)
  downloadBlob(
    new Blob([source], {type: 'image/svg+xml;charset=utf-8'}),
    getDownloadName(diagram, 'svg'),
  )
}

async function downloadPng(diagram: HTMLElement, button: HTMLButtonElement) {
  const svg = diagram.querySelector<SVGSVGElement>('svg')
  if (!svg) return

  button.disabled = true
  const originalLabel = button.getAttribute('aria-label') || '保存为 PNG'
  let sourceUrl: string | undefined

  try {
    const viewBox = svg.viewBox.baseVal
    const rect = svg.getBoundingClientRect()
    const width = Math.max(1, viewBox.width || rect.width)
    const height = Math.max(1, viewBox.height || rect.height)
    const padding = 32
    const maxCanvasSide = 8192
    const scale = Math.min(2, maxCanvasSide / (width + padding * 2), maxCanvasSide / (height + padding * 2))

    const clone = svg.cloneNode(true) as SVGSVGElement
    clone.setAttribute('xmlns', SVG_NAMESPACE)
    clone.setAttribute('width', String(width))
    clone.setAttribute('height', String(height))
    clone.style.maxWidth = 'none'

    const source = new XMLSerializer().serializeToString(clone)
    sourceUrl = URL.createObjectURL(new Blob([source], {type: 'image/svg+xml;charset=utf-8'}))
    const image = new Image()
    image.decoding = 'async'
    image.src = sourceUrl
    await image.decode()

    const canvas = document.createElement('canvas')
    canvas.width = Math.ceil((width + padding * 2) * scale)
    canvas.height = Math.ceil((height + padding * 2) * scale)
    const context = canvas.getContext('2d')
    if (!context) throw new Error('Canvas 2D context is unavailable')

    context.scale(scale, scale)
    context.fillStyle = document.documentElement.classList.contains('dark') ? '#1b1b1f' : '#ffffff'
    context.fillRect(0, 0, width + padding * 2, height + padding * 2)
    context.drawImage(image, padding, padding, width, height)

    const png = await new Promise<Blob>((resolve, reject) => {
      canvas.toBlob(blob => blob ? resolve(blob) : reject(new Error('PNG export failed')), 'image/png')
    })
    downloadBlob(png, getDownloadName(diagram))
  } catch (error) {
    console.warn('Unable to export Mermaid diagram as PNG; downloading SVG instead', error)
    downloadSvg(svg, diagram)
    button.setAttribute('aria-label', 'PNG 导出受限，已保存 SVG')
    button.title = 'PNG 导出受限，已保存 SVG'
    window.setTimeout(() => {
      button.setAttribute('aria-label', originalLabel)
      button.title = originalLabel
    }, 2000)
  } finally {
    if (sourceUrl) URL.revokeObjectURL(sourceUrl)
    button.disabled = false
  }
}

function enhanceDiagram(diagram: HTMLElement) {
  if (diagram.hasAttribute(ENHANCED_ATTRIBUTE)) return
  diagram.setAttribute(ENHANCED_ATTRIBUTE, 'true')

  const wrapper = document.createElement('div')
  wrapper.className = 'mermaid-viewer'
  diagram.parentNode?.insertBefore(wrapper, diagram)
  wrapper.appendChild(diagram)

  const toolbar = document.createElement('div')
  toolbar.className = 'mermaid-actions'
  toolbar.setAttribute('role', 'toolbar')
  toolbar.setAttribute('aria-label', '流程图操作')

  const source = getDiagramSource(diagram)
  const sourceView = document.createElement('div')
  sourceView.className = 'mermaid-source-view'
  sourceView.hidden = true

  const sourceCode = document.createElement('code')
  sourceCode.textContent = source || ''
  const sourcePre = document.createElement('pre')
  sourcePre.tabIndex = 0
  sourcePre.appendChild(sourceCode)

  const copy = createIconButton('复制源码', `
    <svg viewBox="0 0 24 24" aria-hidden="true">
      <rect width="14" height="14" x="8" y="8" rx="2" ry="2"/>
      <path d="M4 16c-1.1 0-2-.9-2-2V4c0-1.1.9-2 2-2h10c1.1 0 2 .9 2 2"/>
    </svg>`)
  copy.classList.add('mermaid-source-copy')
  copy.addEventListener('click', async () => {
    if (!source) return
    try {
      await copyText(source)
      copy.setAttribute('aria-label', '已复制源码')
      copy.title = '已复制源码'
    } catch (error) {
      console.warn('Unable to copy Mermaid source', error)
      copy.setAttribute('aria-label', '复制失败')
      copy.title = '复制失败'
    }
    window.setTimeout(() => {
      copy.setAttribute('aria-label', '复制源码')
      copy.title = '复制源码'
    }, 1500)
  })

  sourceView.append(copy, sourcePre)

  const sourceToggle = createIconButton('查看源码', `
    <svg viewBox="0 0 24 24" aria-hidden="true">
      <path d="m18 16 4-4-4-4M6 8l-4 4 4 4M14.5 4l-5 16"/>
    </svg>`)
  sourceToggle.classList.add('mermaid-action--source')
  sourceToggle.setAttribute('aria-pressed', 'false')
  sourceToggle.disabled = !source
  sourceToggle.addEventListener('click', () => {
    setSourceMode(
      wrapper,
      diagram,
      sourceView,
      sourceToggle,
      !wrapper.classList.contains('is-source-mode'),
    )
  })

  const fullscreen = createIconButton('全屏查看', `
    <svg viewBox="0 0 24 24" aria-hidden="true">
      <path d="M8 3H5a2 2 0 0 0-2 2v3M16 3h3a2 2 0 0 1 2 2v3M8 21H5a2 2 0 0 1-2-2v-3M16 21h3a2 2 0 0 0 2-2v-3"/>
    </svg>`)
  fullscreen.classList.add('mermaid-action--fullscreen')
  fullscreen.dataset.expanded = 'false'
  fullscreen.setAttribute('aria-pressed', 'false')
  fullscreen.addEventListener('click', () => {
    if (wrapper.classList.contains('is-fallback-fullscreen')) {
      closeFallbackFullscreen()
      setFullscreenLabel(fullscreen, false)
      return
    }
    toggleFullscreen(wrapper, fullscreen).catch(console.error)
  })

  const download = createIconButton('保存为 PNG', `
    <svg viewBox="0 0 24 24" aria-hidden="true">
      <path d="M12 3v12m0 0 5-5m-5 5-5-5M5 21h14"/>
    </svg>`)
  download.addEventListener('click', () => downloadPng(diagram, download))

  toolbar.append(sourceToggle, fullscreen, download)
  wrapper.append(sourceView, toolbar)
}

function enhanceAllDiagrams() {
  document.querySelectorAll<HTMLElement>('.vp-doc .mermaid').forEach(enhanceDiagram)
}

function handleFullscreenChange() {
  document.querySelectorAll<HTMLElement>('.mermaid-viewer').forEach(wrapper => {
    const button = wrapper.querySelector<HTMLButtonElement>('.mermaid-action--fullscreen')
    if (button) setFullscreenLabel(button, document.fullscreenElement === wrapper)
  })
}

function handleKeydown(event: KeyboardEvent) {
  if (event.key === 'Escape') closeFallbackFullscreen()
}

onMounted(() => {
  enhanceAllDiagrams()
  observer = new MutationObserver(enhanceAllDiagrams)
  observer.observe(document.body, {childList: true, subtree: true})
  document.addEventListener('fullscreenchange', handleFullscreenChange)
  document.addEventListener('keydown', handleKeydown)
})

onBeforeUnmount(() => {
  observer?.disconnect()
  document.removeEventListener('fullscreenchange', handleFullscreenChange)
  document.removeEventListener('keydown', handleKeydown)
  closeFallbackFullscreen()
})
</script>

<template></template>
