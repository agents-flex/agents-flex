import {defineConfig} from 'vitepress'

// https://vitepress.dev/reference/site-config
export default defineConfig({
    lang: 'zh-CN',
    title: "Agents-Flex",
    titleTemplate: ':title - Agents-Flex 官方网站',
    description: "一个优雅的 LLM（大语言模型）应用开发框架",
    lastUpdated: true,

    // logo: '/assets/images/logo02.png',

    themeConfig: {
        outline:{
            label:"章节"
        },
        search: {
            provider: 'local'
        },
        editLink: {
            // pattern: 'https://github.com/agents-flex/agents-flex/edit/main/docs/:path'
            pattern: 'https://gitee.com/agents-flex/agents-flex/edit/main/docs/:path',
            text: '编辑当前页面'
        },
        // https://vitepress.dev/reference/default-theme-config
        // logo: '/assets/images/logo01.png',
        nav: [
            {text: '首页', link: '/'},

            {text: '帮助文档', link: '/zh/intro/what-is-agentsflex'},
            {text: 'ChangeLog', link: '/zh/changes'},
            {
                text: '获取源码', items: [
                    {text: 'Gitee', link: 'https://gitee.com/agents-flex/agents-flex'},
                    {text: 'Github', link: 'https://github.com/agents-flex/agents-flex'},
                    {text: '示例代码', link: 'https://gitee.com/agents-flex/agents-flex-samples'},
                ]
            },
        ],

        sidebar: [
            {
                text: '简介',
                items: [
                    {text: 'agents-Flex 是什么', link: '/zh/intro/what-is-agentsflex'},
                    {text: '快速开始', link: '/zh/intro/getting-started'},
                    {text: 'Maven 依赖', link: '/zh/intro/maven'},
                    {text: 'Gradle 依赖', link: '/zh/intro/gradle'},
                    {text: 'Kotlin 使用', link: '/zh/intro/use-in-kotlin'},
                    {text: '交流群', link: '/zh/intro/qq-group'},
                ]
            },
            {
                text: '基础功能',
                items: [
                    {text: '增、删、改', link: '/zh/base/add-delete-update'},
                    {text: '基础查询', link: '/zh/base/query'},
                    {text: '自动映射', link: '/zh/base/auto-mapping'},
                    {text: '关联查询', link: '/zh/base/relations-query'},
                    {text: '批量操作', link: '/zh/base/batch'},
                ]
            },
            {
                text: '高级功能',
                items: [
                    {text: '@Table 注解', link: '/zh/core/table'},
                    {text: '@Id 注解', link: '/zh/core/id'},
                    {text: '@Column 注解', link: '/zh/core/column'},
                    {text: '逻辑删除', link: '/zh/core/logic-delete'},
                    {text: '乐观锁', link: '/zh/core/version'},
                ]
            },
            {
                text: '其他',
                items: [
                    {text: '代码生成器', link: '/zh/others/codegen'},
                    {text: 'APT 设置', link: '/zh/others/apt'},
                    {text: 'KAPT 设置', link: '/zh/others/kapt'},
                ]
            }
        ],

        footer: {
            message: 'Released under the Apache License.',
            copyright: 'Copyright © 2022-present Agents-Flex. ' +
                '<span style="display: flex;align-items: center;justify-content: center;">' +
                '<span style="font-size: 12px;margin-right:10px;"><a style="color:#777" target="_blank" rel="noopener" href="http://beian.miit.gov.cn/">黔ICP备19009310号-9 </a></span>' +

                '<img src="/assets/images/beian.jpg" style="margin-top: -2px;margin-right: 2px;width: 15px;">' +

                '<a target="_blank" href="http://www.beian.gov.cn/portal/registerSystemInfo?recordcode=52010202003658"' +
                ' style="display:inline-block;text-decoration:none;color:#777;font-size: 12px">贵公网安备 52010202003658 号</a>' +
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
