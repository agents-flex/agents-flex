package com.agentsflex.wiki;

import com.agentsflex.core.util.IOUtil;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;

public class MybatisflexWikiProvider implements WikiProvider{
    @Override
    public Wiki getWiki(String path) {
        File wikiFile = new File("/Users/michael/git/agents-flex/demos/wiki-demo/src/main/resources/mybatis-flex" , path);
        if (wikiFile.exists()) {
            String content;
            try {
                content =  IOUtil.readUtf8(Files.newInputStream(wikiFile.toPath()));
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
            Wiki wiki = Wikis.getWiki(path);
            if (wiki == null){
                wiki = new Wiki(path, wikiFile.getName(), "");
            }
//            wiki.setPath(path);
//            wiki.setTitle(wikiFile.getName());
//            wiki.setDescription("");
            wiki.setContent(content);
            return wiki;
        }
        return null;
    }
}
