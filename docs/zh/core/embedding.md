# Embedding 嵌入

Embedding 我们可以简单的理解为：有一种算法（或模型），能够把高纬数据映射到一个低维度的向量空间的过程，这个映射的过程，本质上是一个数据特征提取的过程。

> 低纬度的向量数据，可以减少数据的复杂性，从而提高模型的训练和推理效率。

## 示例代码

```java
Llm llm = OpenAiLlm.of("sk-rts5NF6n*******");
VectorData embeddings = llm.embed(Document.of("some document text"));
System.out.println(Arrays.toString(embeddings.getVector()));
```
