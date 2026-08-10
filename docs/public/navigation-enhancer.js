(function () {
  'use strict'

  function normalizePath(value) {
    var path = value || '/'
    try {
      path = new URL(path, window.location.origin).pathname
    } catch (_) {}
    path = path.replace(/\/index\.html$/, '/').replace(/\.html$/, '')
    return path.length > 1 ? path.replace(/\/$/, '') : '/'
  }

  function updateSidebar() {
    var current = normalizePath(window.location.pathname)
    document.querySelectorAll('.VPSidebarItem').forEach(function (item) {
      var link = item.querySelector(':scope > .item > .VPLink, :scope > .item > a')
      if (!link) return
      var active = normalizePath(link.getAttribute('href')) === current
      item.classList.toggle('is-active', active)
      link.classList.toggle('active', active)
      if (active) item.setAttribute('aria-current', 'page')
      else item.removeAttribute('aria-current')
    })
  }

  function updateOutline() {
    var outline = document.querySelector('.VPDocOutlineItem.root')
    var doc = document.querySelector('.vp-doc')
    if (!outline || !doc) return
    var headings = Array.prototype.slice.call(doc.querySelectorAll('h2, h3'))
    var list = outline
    list.innerHTML = ''
    var roots = []
    headings.forEach(function (heading) {
      if (!heading.id) return
      var node = {heading: heading, children: []}
      if (heading.tagName === 'H2' || roots.length === 0) roots.push(node)
      else roots[roots.length - 1].children.push(node)
    })

    function appendNode(parent, node, nested) {
      var item = document.createElement('li')
      var link = document.createElement('a')
      link.className = nested ? 'outline-link nested' : 'outline-link'
      link.href = '#' + node.heading.id
      link.textContent = node.heading.textContent.replace(/[​\u200b]/g, '').trim()
      item.appendChild(link)
      if (node.children.length) {
        var childList = document.createElement('ul')
        childList.className = 'VPDocOutlineItem nested'
        node.children.forEach(function (child) { appendNode(childList, child, true) })
        item.appendChild(childList)
      }
      parent.appendChild(item)
    }

    roots.forEach(function (node) { appendNode(list, node, false) })

    function syncActiveHeading() {
      var active = headings[0]
      headings.forEach(function (heading) {
        if (heading.getBoundingClientRect().top <= 140) active = heading
      })
      outline.querySelectorAll('.outline-link').forEach(function (link) {
        link.classList.toggle('active', active && link.getAttribute('href') === '#' + active.id)
      })
    }
    syncActiveHeading()
    window.addEventListener('scroll', syncActiveHeading, {passive: true})

    var container = outline.closest('.VPDocAsideOutline')
    if (container) container.classList.toggle('has-outline', headings.length > 0)
  }

  function updateThemeControls() {
    var dark = document.documentElement.classList.contains('dark')
    document.querySelectorAll('.VPSwitchAppearance').forEach(function (button) {
      button.setAttribute('aria-checked', dark ? 'true' : 'false')
    })
  }

  function setupThemeToggle() {
    updateThemeControls()
    document.addEventListener('click', function (event) {
      var button = event.target.closest && event.target.closest('.VPSwitchAppearance')
      if (!button) return
      event.preventDefault()
      var dark = !document.documentElement.classList.contains('dark')
      document.documentElement.classList.toggle('dark', dark)
      localStorage.setItem('vitepress-theme-appearance', dark ? 'dark' : 'light')
      updateThemeControls()
    })
  }

  function enhance() {
    updateSidebar()
    updateOutline()
    setupThemeToggle()
  }

  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', enhance)
  else enhance()
})()
