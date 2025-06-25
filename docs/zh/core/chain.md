# Chain 执行链

执行链（Chain）是由多个节点（Node）按照一定顺序编排、组合而成的链条，用于实现复杂任务的自动化执行。

在执行链中，通常包含开始节点、中间节点和结束节点。通过合理组织这些节点，可以实现各种复杂的业务逻辑。

在 agents-flex 中，内置了多种节点类型，可以满足不同场景的需求。此外，还可以与 [Tinyflow](https://www.tinyflow.cn/zh/core/node.html) 结合使用，进一步扩展节点功能，获取更多强大的节点类型。关于两者结合的具体案例，可以参考 [AIFlowy](https://aiflowy.tech/zh/product/workflow/what_is_workflow.html) 工作流部分的文档。

## 简单示例

以下是一个简单的执行链示例，展示了如何创建包含开始节点、中间节点和结束节点的执行链，并设置节点之间的连接关系，最后执行该执行链：

```java
public static void main(String[] args) {
    // 创建执行链
    Chain chain = new Chain();

    // 创建开始节点
    StartNode startNode = new StartNode();
    startNode.setId("1");
    chain.addNode(startNode);

    // 创建中间节点（动态代码节点）
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

    // 执行执行链
    Map<String, Object> result = chain.executeForResult(new HashMap<>());
    System.out.println(result);
}
```

## 节点

在 agents-flex 中，所有节点都继承自 `com.agentsflex.core.chain.node.BaseNode` 类。除了公共参数外，每个节点类型还具有各自的特定参数。以下是两种常见节点类型的介绍：

### 大模型节点（LlmNode）

大模型节点用于调用大型语言模型（LLM），实现如文本生成、问答等复杂功能。其主要参数包括：

- `llm`：大模型实例。
- `chatOptions`：聊天参数，默认为 `ChatOptions.DEFAULT`。
- `userPrompt`：用户提示词。
- `userPromptTemplate`：用户提示词模板。
- `systemPrompt`：系统提示词。
- `systemPromptTemplate`：系统提示词模板。
- `outType`：输出类型，可选值包括 `text`、`markdown`、`json` 等。

代码如下：

```java
public class LlmNode extends BaseNode {
    protected Llm llm;
    protected ChatOptions chatOptions = ChatOptions.DEFAULT;
    protected String userPrompt;
    protected TextPromptTemplate userPromptTemplate;
    protected String systemPrompt;
    protected TextPromptTemplate systemPromptTemplate;
    protected String outType = "text"; //text markdown json
    // 其他相关代码...
}
```

### 动态代码节点（CodeNode）

动态代码节点允许用户编写自定义代码逻辑，并在执行链中动态执行，从而增加了执行链的灵活性。
其核心参数为 `code`，表示要执行的代码。代码如下：

```java
public abstract class CodeNode extends BaseNode {
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

目前支持以下几种语言：
- JavaScript（`com.agentsflex.chain.node.JsExecNode`）
- Groovy（`com.agentsflex.chain.node.GroovyExecNode`）
- QLExpress（`com.agentsflex.chain.node.QLExpressExecNode`）

## 边（ChainEdge）

边（`com.agentsflex.core.chain.ChainEdge`）用于连接节点，确保节点按照指定的顺序执行。可以通过调用 `setCondition` 方法为边设置执行条件，只有当满足条件时，才会执行下一个节点。

## 异步执行
`ChainNode` 中有一个属性 `async`，用于设置节点是否为异步执行，默认值为 `false`。
设置为 `true` 后，节点将运行在单独的线程中。
## 循环执行
在 agents-flex 中，节点可以设置成循环执行，即重复执行该节点直到满足条件为止。

节点配置属性如下：
```java
    // 是否启用循环执行
    protected boolean loopEnable = false;
    // 循环间隔时间（毫秒）
    protected long loopIntervalMs = 1000;
    // 跳出循环的条件表达式（如：Groovy/SpEL 表达式）
    protected NodeCondition loopBreakCondition;
    // 0 表示不限制循环次数
    protected int maxLoopCount = 0;
```
示例代码：
```java
public static void main(String[] args) {

        Chain chain = new Chain();

        ChainNode a = new ChainNode() {
            @Override
            protected Map<String, Object> execute(Chain chain) {
                System.out.println("a 节点 >>> execute!");
                return Maps.of();
            }
        };
        a.setId("a");
        chain.addNode(a);

        ChainNode b = new ChainNode() {
            @Override
            protected Map<String, Object> execute(Chain chain) {
                System.out.println("b 节点 >>> execute!");
                // 模拟请求执行结果，并存入内存。
                getMemory().put("result", getRes());
                return Maps.of();
            }
        };
        b.setId("b");
        // 节点 b 开启循环执行
        b.setLoopEnable(true);
        // 循环间隔 500ms
        b.setLoopIntervalMs(500);
        // 设置循环结束条件
        b.setLoopBreakCondition(new NodeCondition() {
            @Override
            public boolean check(Chain chain, NodeContext context) {
                ContextMemory memory = context.getCurrentNode().getMemory();
                // 当结果为5时，结束循环。
                return (int) memory.get("result") == 5;
            }
        });

        chain.addNode(b);

        ChainEdge ab = new ChainEdge();
        ab.setSource("a");
        ab.setTarget("b");

        chain.addEdge(ab);

        chain.executeForResult(new HashMap<>());
    }

    public static int getRes() {
        return new Random().nextInt(10);
    }
```
## 执行条件

在 agents-flex 中，节点和边都可以设置执行条件。执行条件通过 `com.agentsflex.core.chain.JavascriptStringCondition` 类实现，这意味着可以直接使用 JavaScript 代码来定义条件逻辑。

示例：
```java
public static void main(String[] args) {
    Chain chain = new Chain();

    TestNode a = new TestNode();
    a.setId("a");
    chain.addNode(a);

    TestNode b = new TestNode(){
        @Override
        protected Map<String, Object> execute(Chain chain) {
            try {
                Thread.sleep(2000);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
            System.out.println("b");
            return Maps.of();
        }
    };
    b.setId("b");
    // 设置 b 节点为异步执行
    b.setAsync(true);

    chain.addNode(b);

    TestNode c = new TestNode();
    c.setId("c");
    chain.addNode(c);

    TestNode d = new TestNode() {
        @Override
        protected Map<String, Object> execute(Chain chain) {
            System.out.println("d: "+ Thread.currentThread().getId());
            return Maps.of();
        }
    };
    d.setId("d");
    // 此处设置 d 节点的条件为上游节点执行完毕后才执行
    d.setCondition(new JavascriptStringCondition("_context.isUpstreamFullyExecuted()"));
    chain.addNode(d);

    ChainEdge ab = new ChainEdge();
    ab.setSource("a");
    ab.setTarget("b");
    chain.addEdge(ab);

    ChainEdge ac = new ChainEdge();
    ac.setSource("a");
    ac.setTarget("c");
    chain.addEdge(ac);


    ChainEdge bd = new ChainEdge();
    bd.setSource("b");
    bd.setTarget("d");
    chain.addEdge(bd);

    ChainEdge cd = new ChainEdge();
    cd.setSource("c");
    cd.setTarget("d");
    chain.addEdge(cd);

    /**
     * 最终执行顺序：
     *      B
     *   ↗   ↘
     * A         D
     *   ↘   ↗
     *      C
     */
    chain.executeForResult(new HashMap<>());
}
```
