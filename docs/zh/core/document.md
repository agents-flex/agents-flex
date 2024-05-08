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

在文档模块了，除了 `Document` 本身以外，还提供了如下几种组件：

- **DocumentLoader**：文档加载器，用于从不同的地方加载（读取）内容（比如 本地磁盘、数据库、网站 等）文档内容。
- **DocumentParser**：文档解析器，用于对不同类型的文档进行解析，最终得到 `Document` 对象，比如解析 word、pdf、html 等等。
- **DocumentSplitter**：文档分割器，用于对大文档进行分割，生成多个小文档，方便 Embedding 计算以及向量数据库存储。

##  DocumentLoader 文档加载器

在 Agents-Flex 中，提供了如下两种文档加载器（未来会提供更多的类型）：

- **FileDocumentLoader**： 文件文档加载器
- **HttpDocumentLoader**： http 文档加载器

未来我们会新增更多类型的文档加载器，比如数据库加载、FTP 加载。或者特定的领域的加载器，比如微信公众号加载器等。用户也可以实现自己的加载，欢迎大家参与和分享。

## DocumentParser 文档解析器

文档解析器用于对不同类型的文档进行解析，最终得到 `Document` 对象，Agents-Flex 已内置的文档解析器如下：

- **PdfBoxDocumentParser**：对 PDF 解析
- **PoiDocumentParser**：对 word 文档进行解析

## DocumentSplitter 文档分割器

文档分割器是用对对大文档进行分割为多个小文档的场景，不同的分割器可以用于不同的分割场景。目前 Agents-Flex 的提供的文档分割器如下：

- **SimpleDocumentSplitter**：可以通过正则表达式进行分割
- **MarkdownDocumentSplitter**：对 Markdown 内容进行分割
- **ParagraphDocumentSplitter**：通过段落进行分割
