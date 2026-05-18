# Document

In Agents-Flex, `Document` is a document object with vector data. It is defined as follows:

```java
public class Document extends VectorData {

    /**
     * Document ID
     */
    private Object id;

    /**
     * Document content
     */
    private String content;
}
```


- id: Document id
- content: Document content

Since it contains vector data, it can be stored in a VectorStore.

In the `Document` module, besides the  `Document` itself, the following components are also provided:

- **DocumentSplitter**: A document splitter used to split large documents into multiple smaller documents, facilitating Embedding computation and storage in vector databases.

## DocumentSplitter

Document splitters are used to split large documents into multiple smaller documents, with different splitters suitable for different splitting scenarios. Currently, Agents-Flex provides the following document splitters:

- **SimpleDocumentSplitter**: Splits documents using regular expressions
- **MarkdownDocumentSplitter**: Splits Markdown content
- **ParagraphDocumentSplitter**: Splits documents by paragraphs
