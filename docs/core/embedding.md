# Embedding

Embedding can be understood simply as follows: there is an algorithm (or model) that can map high-dimensional data to a low-dimensional vector space. This mapping process is essentially a process of feature extraction.

> Low-dimensional vector data can reduce the complexity of data, thereby improving the efficiency of model training and inference.

## Samples

```java
Llm llm = OpenAiLlm.of("sk-rts5NF6n*******");
VectorData embeddings = llm.embed(Document.of("some document text"));
System.out.println(Arrays.toString(embeddings.getVector()));
```
