<!--
  -  Copyright (c) 2022-2023, Mybatis-Flex (fuhai999@gmail.com).
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

const {Layout} = DefaultTheme
</script>


<template>
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
