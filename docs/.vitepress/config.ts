import {defineConfig} from 'vitepress'

// https://vitepress.dev/reference/site-config
export default defineConfig({
    title: "Agents-Flex",
    titleTemplate: ':title - Agents-Flex Official website',
    description: "A Java framework for LLM applications",
    lastUpdated: true,
    appearance:"dark",

    // logo: '/assets/images/logo02.png',

    locales:{
        root: {
            label: 'English',
            lang: 'en'
        },
        zh:{
            titleTemplate: ':title - Agents-Flex 官方网站',
            description: "一个优雅的 LLM（大语言模型）应用开发框架",
            label: '简体中文',
            lang: 'zh', // 可选，将作为 `lang` 属性添加到 `html` 标签中

            themeConfig: {
                outline: {
                    label: "章节"
                },
                editLink: {
                    pattern: 'https://github.com/agents-flex/agents-flex/edit/main/docs/:path',
                    text: '编辑当前页面'
                },

                nav: [
                    {text: '首页', link: '/'},
                    {text: '帮助文档', link: '/zh/intro/what-is-agentsflex'},
                    {text: 'ChangeLog', link: 'https://github.com/agents-flex/agents-flex/blob/main/changes.md'},
                ],

                sidebar: [
                    {
                        text: '快速开始',
                        items: [
                            {text: 'Agents-Flex 是什么', link: '/zh/intro/what-is-agentsflex'},
                            {text: '快速开始', link: '/zh/intro/getting-started'},
                            {text: 'Maven 依赖', link: '/zh/intro/maven'},
                            {text: '微信交流群', link: '/zh/intro/communication'},
                        ]
                    },
                    {
                        text: '核心模块',
                        items: [
                            {text: 'LLMs 大语言模型', link: '/zh/core/llms'},
                            // {text: 'Prompt 提示词', link: '/zh/core/prompt'},
                            // {text: 'Chat 对话', link: '/zh/core/chat'},
                            // {text: 'Function Calling 方法调用', link: '/zh/core/function-calling'},
                            {text: 'Memory 记忆', link: '/zh/core/memory'},
                            {text: 'Embedding 嵌入', link: '/zh/core/embedding'},
                            {text: 'Store 存储', link: '/zh/core/store'},
                            {text: 'Document 文档', link: '/zh/core/document'},
                            {text: 'Agent 智能体', link: '/zh/core/agent'},
                            {text: 'Chain 执行链', link: '/zh/core/chain'},
                        ]
                    },
                    {
                        text: '基础示例',
                        items: [
                            {text: '简单对话', link: '/zh/samples/chat'},
                            {text: '历史对话', link: '/zh/samples/chat-with-memory'},
                            {text: 'RAG 应用', link: '/zh/samples/rag'},
                        ]
                    }
                ],
            }

        },
    },

    themeConfig: {
        // logo: '/assets/images/logo.png',
        search: {
            provider: 'local'
        },
        editLink: {
            pattern: 'https://github.com/agents-flex/agents-flex/edit/main/docs/:path'
        },
        socialLinks: [
            { icon: 'github', link: 'https://github.com/agents-flex/agents-flex' },
        ],

        nav: [
            {text: 'Home', link: '/'},
            {text: 'Document', link: '/intro/what-is-agentsflex'},
            {text: 'ChangeLog', link: 'https://github.com/agents-flex/agents-flex/blob/main/changes.md'},
        ],

        sidebar: [
            {
                text: 'Quick Start',
                items: [
                    {text: 'What is Agents-Flex', link: '/intro/what-is-agentsflex'},
                    {text: 'Quick Start', link: '/intro/getting-started'},
                    {text: 'Maven Dependencies', link: '/intro/maven'},
                    {text: 'Communication', link: '/intro/communication'},
                ]
            },
            {
                text: 'Core Modules',
                items: [
                    {text: 'LLMs', link: '/core/llms'},
                    // {text: 'Prompt 提示词', link: '/zh/core/prompt'},
                    // {text: 'Chat 对话', link: '/zh/core/chat'},
                    // {text: 'Function Calling 方法调用', link: '/zh/core/function-calling'},
                    {text: 'Memory', link: '/core/memory'},
                    {text: 'Embedding', link: '/core/embedding'},
                    {text: 'Store', link: '/core/store'},
                    {text: 'Document', link: '/core/document'},
                    {text: 'Agent', link: '/core/agent'},
                    {text: 'Chain', link: '/core/chain'},
                ]
            },
            {
                text: 'Samples',
                items: [
                    {text: 'Simple Chat', link: '/samples/chat'},
                    {text: 'Chat With Histories', link: '/samples/chat-with-memory'},
                    {text: 'RAG Application', link: '/samples/rag'},
                ]
            }
        ],

        footer: {
            message: 'Released under the Apache License.',
            copyright: 'Copyright © 2022-present Agents-Flex. ' +
                '<span style="display: flex;align-items: center;justify-content: center;">' +
                '<span style="font-size: 12px;margin-right:10px;"><a style="color:#777" target="_blank" rel="noopener" href="http://beian.miit.gov.cn/">黔ICP备19009310号-13 </a></span>' +
                '</span>'
        }
    },
    head: [
        [
            'link', {rel: 'icon', href: '/assets/images/logo02.png'}
        ],

        [// 添加百度统计
            "script",
            {},
            `
      var _hmt = _hmt || [];
      (function() {
        var hm = document.createElement("script");
        hm.src = "https://hm.baidu.com/hm.js?88ab4cfa533c8000717a434501beba56";
        var s = document.getElementsByTagName("script")[0];
        s.parentNode.insertBefore(hm, s);
      })();
        `
        ],

        [// 自动跳转 https
            "script",
            {},
            `
        if (location.protocol !== 'https:' && location.hostname != 'localhost') {
            location.href = 'https://' + location.hostname + location.pathname + location.search;
        }
        `
        ]
    ],
})
