# Chain 执行链

执行链，是有多个 Node 进行编排、组合形成的一个链条⛓。

在 Chain 执行链中，一般情况下是由开始节点、中间节点、结束节点组成。

agents-flex 中内置了多种节点，
可配合 [tinyflow](https://www.tinyflow.cn/zh/core/node.html)
一起使用，获得更多节点功能。

两者结合的具体案例可参考
[AIFlowy](https://aiflowy.tech/zh/product/workflow/what_is_workflow.html)
工作流部分的文档。

## 简单示例
```java
public static void main(String[] args) {

        // 创建执行链
        Chain chain = new Chain();

        // 创建开始节点
        StartNode startNode = new StartNode();
        startNode.setId("1");
        chain.addNode(startNode);

        // 创建中间节点
        JsExecNode jsExecNode = new JsExecNode();
        jsExecNode.setId("2");
        jsExecNode.setCode("console.log('hello world')");
        chain.addNode(jsExecNode);

        // 创建结束节点
        EndNode endNode = new EndNode();
        endNode.setId("3");
        endNode.setMessage("success");
        chain.addNode(endNode);

        // 创建 1-2 的边
        ChainEdge edge12 = new ChainEdge();
        edge12.setSource("1");
        edge12.setTarget("2");
        chain.addEdge(edge12);

        // 创建 2-3 的边
        ChainEdge edge23 = new ChainEdge();
        edge23.setSource("2");
        edge23.setTarget("3");
        chain.addEdge(edge23);

        // 1 -> 2 -> 3
        Map<String, Object> result = chain.executeForResult(new HashMap<>());
        System.out.println(result);

    }
```
## 节点
在 agents-flex 中，所有节点都继承 `com.agentsflex.core.chain.node.BaseNode`
除公共参数外，每个节点各自的参数不同。
### 大模型节点
```java
public class LlmNode extends BaseNode {
    // 大模型
    protected Llm llm;
    // 聊天参数
    protected ChatOptions chatOptions = ChatOptions.DEFAULT;
    // 用户提示词
    protected String userPrompt;
    // 用户提示词模板
    protected TextPromptTemplate userPromptTemplate;
    // 系统提示词
    protected String systemPrompt;
    // 系统提示词模板
    protected TextPromptTemplate systemPromptTemplate;
    // 输出类型
    protected String outType = "text"; //text markdown json
    ......
}
```
### 动态代码节点
可以动态执行代码，增加了灵活度。
```java
public abstract class CodeNode extends BaseNode {
    // 代码
    protected String code;

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }

    @Override
    protected Map<String, Object> execute(Chain chain) {
        if (StringUtil.noText(code)) {
            throw new IllegalStateException("Code is null or blank.");
        }

        return executeCode(this.code, chain);
    }
    // 不同语言有不同的实现
    protected abstract Map<String, Object> executeCode(String code, Chain chain);
}
```
目前支持三种语言：
- JavaScript
- Groovy
- QLExpress

## 边
`com.agentsflex.core.chain.ChainEdge`
边是用来连接节点的，保证了节点之间的执行顺序。

可通过 `setCondition` 方法设置条件，只有满足条件时，才会执行到下一个节点。

## 执行条件

无论是`节点`还是`边`，都可以设置执行条件。

在 agents-flex 中，是通过 `com.agentsflex.core.chain.JavascriptStringCondition` 来添加执行条件的。
这也就意味着可以直接使用 `JavaScript` 代码来设置条件。
