# Tool.Builder
<div v-pre>


`Tool.Builder` 是 Agents-Flex 中用于 **以编程方式创建 Tool 实例** 的构建器。相比通过注解自动扫描工具方法（ToolScanner），`Tool.Builder` 适用于构建 **动态工具、运行时工具、基于函数式接口的工具**。

它为开发者提供了高度灵活的工具声明方式，使得工具能力不仅可以来源于静态方法，也可以由匿名函数、Lambda、闭包动态实现。


## 1. Tool 的基本概念

在 Agents-Flex 中，**Tool 是让 LLM 执行外部能力的桥梁**，例如：

* 访问数据库
* 调用第三方 API
* 读写文件
* 执行业务逻辑
* 调用工作流节点

一个 Tool 由 4 部分组成：

| 部分          | 说明                       |
| -- |--------------------------|
| name        | 工具名称，LLM 用来选择工具          |
| description | 工具的用途描述                  |
| parameters  | 参数列表（由 `Parameter` 定义结构） |
| invoke      | 执行逻辑（由你提供的函数）            |

而 **`Tool.Builder` 就是用于创建 Tool 的标准流程。**


## 2. 为什么需要 Tool.Builder？

除了通过注解扫描生成 Tool 外，框架还支持 **在运行时动态构建工具能力**，Tool.Builder 适用于以下场景：

### 场景 1：在运行时注册工具

例如根据用户的配置动态构建工具能力。

### 场景 2：工具的逻辑来自 Lambda / 匿名函数

例如快速定义逻辑：

```java
Tool tool = Tool.builder()
    .name("add")
    .description("加法运算")
    .function(args -> (int) args.get("a") + (int) args.get("b"))
    .build();
```

### 场景 3：需要自由控制参数结构

参数可以任意组合，不依赖注解扫描。

### 场景 4：构建 DSL、工作流节点、插件系统

Tool.Builder 非常适合插件化系统，将任意业务代码包装为 LLM 可调用的能力。



## 3. 快速入门

下面构建一个简单的工具：两个数字相加。

```java
Tool addTool = Tool.builder()
    .name("add")
    .description("执行两个数字的加法")
    .addParameter(Parameter.builder()
        .name("a").type("number").description("第一个数字").required(true).build())
    .addParameter(Parameter.builder()
        .name("b").type("number").description("第二个数字").required(true).build())
    .function(args -> {
        int a = (int) args.get("a");
        int b = (int) args.get("b");
        return a + b;
    })
    .build();
```

调用：

```java
Object result = addTool.invoke(Map.of("a", 5, "b", 7));
// result = 12
```



## 4. 进阶用法

### 4.1 构建复杂的嵌套参数结构

Parameter 支持 children，因此可以定义对象结构：

```java
Parameter userParam = Parameter.builder()
    .name("user")
    .type("object")
    .addChild(Parameter.builder().name("id").type("string").build())
    .addChild(Parameter.builder().name("age").type("number").build())
    .build();
```

注册工具：

```java
Tool tool = Tool.builder()
    .name("getUserInfo")
    .description("根据用户对象获取处理结果")
    .addParameter(userParam)
    .function(args -> {
        Map<String, Object> user = (Map<String, Object>) args.get("user");
        return "User ID = " + user.get("id");
    })
    .build();
```



### 4.2 动态生成工具（例如插件式工具系统）

Tool.Builder 与 Lambda 非常契合，可以根据配置列表批量生成工具：

```java
List<Tool> tools = configs.stream().map(cfg ->
    Tool.builder()
        .name(cfg.getName())
        .description(cfg.getDescription())
        .parameters(cfg.getParameters())
        .function(cfg.getHandler())
        .build()
).toList();
```



### 4.3 结合工作流节点使用

你可以将每一个工作流节点包装为 Tool，让 LLM 自动调度工作流步骤：

```java
Tool nodeTool = Tool.builder()
    .name("workflow_node_12")
    .description("执行工作流节点12")
    .function(context::runNode12)
    .build();
```



## 5. Tool.Builder 与 ToolScanner 的关系

| 构建方式         | 适用场景        | 说明        |
|--------------| -- | -- |
| ToolScanner  | 批量扫描类中的注解方法 | 适合静态工具    |
| Tool.Builder | 完全编程式构建     | 最灵活，可动态注册 |

二者互补：

* 当工具逻辑固定 → 用注解 + ToolScanner
* 当工具逻辑运行时动态生成 → 用 Tool.Builder



## 6. 核心组件说明

### Tool.Builder 字段

| 字段            | 作用                                           |
| - | -- |
| `name`        | 工具名                                          |
| `description` | 工具说明                                         |
| `parameters`  | 参数列表（Parameter[]）                            |
| `invoker`     | 执行逻辑：`Function<Map<String, Object>, Object>` |

它最终构建出 `FunctionTool`，这是一个具体可执行的 Tool 实现。



</div>
