# Document 文档

在 Agents-Flex 中，`Document` 是一个带有向量数据的文档对象。其定义如下：

```java
public class Document extends VectorData {

    /**
     * 文档 ID
     */
    private Object id;

    /**
     * 文档内容
     */
    private String content;
}
```

- id：文档 id
- content：文档内容

由于其带有向量数据，因此可以被存储在向量数据库（VectorStore）中。

在文档模块中，除了 `Document` 本身以外，还提供了如下组件：

- **DocumentSplitter**：文档分割器，用于对大文档进行分割，生成多个小文档，方便 Embedding 计算以及向量数据库存储。


## DocumentSplitter 文档分割器

文档分割器是用对对大文档进行分割为多个小文档的场景，不同的分割器可以用于不同的分割场景。目前 Agents-Flex 的提供的文档分割器如下：

- **SimpleTokenizeSplitter**：可以通过正则表达式进行分割
- **MarkdownHeaderSplitter**：对 Markdown 内容进行分割
- **SimpleDocumentSplitter**：通过设置分段大小来分割
- **RegexDocumentSplitter**：通过正则表达式分割
- **AIDocumentSplitter**：通过 AI 来进行分割
