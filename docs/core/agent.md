# Agent

`AI Agent` is an abstract concept with various definitions and interpretations within the industry.

The author understands an agent as a "person" with an identity, a name, memory, and the ability to execute tasks. During execution, an agent can engage in planning, use tools, or perform critical thinking.

It is entirely up to the developer to decide how an agent engages in planning, uses tools, or performs critical thinking, rather than being determined by the Agents-Flex framework.

Therefore, in Agents-Flex, the Agent class is defined as follows:

```java
public abstract class Agent {
    private Object id;
    private String name;
    private ContextMemory memory;

    public abstract Output execute(Map<String, Object> variables, Chain chain);
}
```

- id: Defines the agent's id, which should be unique for each agent.
- name: The agent's name (or alias)
- memory: Memory storage
- `execute(Map<String, Object> variables, Chain chain)`: Execution method to be implemented by subclasses.

## Example Code

Customizing an Agent to Generate SQL Content Based on DDL Descriptions:

```java
public class SampleLlmAgent extends LLMAgent {

    public SampleLlmAgent(Llm llm) {
        this.llm = llm;
        this.prompt = "You are now a MySQL database architect. Please, based on the following table structure information," +
            "Help me generate executable DDL statements so that I can use them to create the table structure in MySQL.\n" +
            "Note: \n" +
            "Please return the DDL content directly, without explanation or any additional content other than the DDL statements.\n" +
            "\nThe following is the content of the table information:\n\n{ddlInfo}";
    }

    @Override
    protected Output onMessage(AiMessage aiMessage) {
        String sqlContent = aiMessage.getContent()
            .replace("```sql", "")
            .replace("```", "");
        return Output.ofValue(sqlContent);
    }
}
```
Next, we execute this agent:

```java
public static void main(String[] args) {
    Llm llm = new OpenAiLlm.of("sk-rts5NF6n*******");

    SampleLlmAgent agent = new SampleLlmAgent(llm);

    String ddlInfo = "Table Name student，Fields id,name";

    Map<String, Object> variables = new HashMap<>();
    variables.put("ddlInfo", ddlInfo);

    Output output = agent.execute(variables, null);
    System.out.println(output.getValue());
}
```

` System.out.println` The output result is as follows:

```sql
CREATE TABLE `student` (
  `id` INT NOT NULL AUTO_INCREMENT COMMENT 'StudentID',
  `name` VARCHAR(255) COMMENT 'StudentName',
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
```
