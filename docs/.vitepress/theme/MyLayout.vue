<!--
  -  Copyright (c) 2022-2023, Agents-Flex (fuhai999@gmail.com).
  -  <p>
  -  Licensed under the Apache License, Version 2.0 (the "License");
  -  you may not use this file except in compliance with the License.
  -  You may obtain a copy of the License at
  -  <p>
  -  http://www.apache.org/licenses/LICENSE-2.0
  -  <p>
  -  Unless required by applicable law or agreed to in writing, software
  -  distributed under the License is distributed on an "AS IS" BASIS,
  -  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
  -  See the License for the specific language governing permissions and
  -  limitations under the License.
  -->

<!--.vitepress/theme/MyLayout.vue-->

<style>
.banner-home {
  display: flex;
  align-items: center;
  margin: 60px auto;
  width: 50%;
  justify-content: center;
}

.banner-home img {
  max-width: 100%;
  border-radius: 3px;
}

@media screen and (max-width: 800px) {
  .banner-home {
    width: 90%;
    margin: 30px auto;
  }
}
</style>

<script setup>
import DefaultTheme from 'vitepress/theme'
import { nextTick, onBeforeUnmount, onMounted } from 'vue'
import MermaidEnhancer from './MermaidEnhancer.vue'

const {Layout} = DefaultTheme

function getMenuTrigger() {
  return document.querySelector('.VPNavBarHamburger[aria-controls="VPNavScreen"]')
}

function onNavigationClick(event) {
  const trigger = event.target.closest?.('.VPNavBarHamburger')
  if (!trigger) return

  nextTick(() => {
    if (trigger.getAttribute('aria-expanded') !== 'true') return
    document.querySelector('#VPNavScreen a, #VPNavScreen button')?.focus()
  })
}

function onNavigationKeydown(event) {
  if (event.key !== 'Escape') return

  const trigger = getMenuTrigger()
  if (trigger?.getAttribute('aria-expanded') !== 'true') return

  event.preventDefault()
  trigger.click()
  nextTick(() => trigger.focus())
}

onMounted(() => {
  document.addEventListener('click', onNavigationClick)
  document.addEventListener('keydown', onNavigationKeydown)
})

onBeforeUnmount(() => {
  document.removeEventListener('click', onNavigationClick)
  document.removeEventListener('keydown', onNavigationKeydown)
})
</script>


<template>
  <MermaidEnhancer />
  <Layout>

    <!--docs: https://vitepress.dev/guide/extending-default-theme#layout-slots-->
<!--    <template #doc-before>-->
<!--      <div style="margin-bottom: 30px">-->
<!--        <a href="https://aiadmin.cc" target="_blank">-->
<!--          <img src="/assets/images/admin-banner.jpg">-->
<!--        </a>-->
<!--      </div>-->
<!--    </template>-->

<!--    <template #home-features-after>-->
<!--      <div class="banner-home">-->
<!--        <a href="https://aiadmin.cc" target="_blank">-->
<!--          <img src="/assets/images/admin-banner.jpg">-->
<!--        </a>-->
<!--      </div>-->
<!--    </template>-->

  </Layout>
</template>
