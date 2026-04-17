package com.agentsflex.wiki;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class Wikis {

    private static final Map<String, Wiki> wikis = new HashMap<>();

    private static void addWiki(Wiki wiki) {
        wikis.put(wiki.getPath(), wiki);
    }

    static {
        addWiki(new Wiki("active-record.md", "Active Record",
            "模式中，对象中既有持久存储的数据，也有针对数据的操作。Active Record 模式把数据存取逻辑作为对象的一部分，处理对象的用户知道如何把数据写入数据库，还知道如何从数据库中读出数据"));

        addWiki(new Wiki("add-delete-update.md", "MyBatis-Flex 的增删改功能",
            "MyBatis-Flex 对数据库的增删改操作文档"));

        addWiki(new Wiki("auto-mapping.md", "自动映射",
            "在 MyBatis-Flex 中，内置了非常智能的 **自动映射** 功能，能够使得我们在查询数据的时候，从数据结果集绑定到实体类（或者 VO、DTO等）变得极其简单易用。"));

        addWiki(new Wiki("batch.md", "批量查询",
            "-"));

        addWiki(new Wiki("chain.md", "链式查询操作和链式操作",
            "-"));

        addWiki(new Wiki("configuration.md", "MyBatis-Flex 的 SpringBoot 或 Solon 配置文件",
            "-"));

        addWiki(new Wiki("db-row.md", "Db + Row 工具的使用",
            "-"));

        addWiki(new Wiki("query.md", "MyBatis-Flex 的查询和分页",
            "-"));

        addWiki(new Wiki("querywrapper.md", "MyBatis-Flex 灵活的 QueryWrapper",
            "-"));

        addWiki(new Wiki("service.md", "Service 接口",
            "-"));

        addWiki(new Wiki("relations-query.md", "MyBatis-Flex 关联查询",
            "-"));

        addWiki(new Wiki("other.md", "关联查询",
            "关于 MyBatis-flex 简介等信息"));
    }

    public static Wiki getWiki(String path) {
        return wikis.get(path);
    }


    public static List<Wiki> getWikis() {
        return new ArrayList<>(wikis.values());
    }
}
