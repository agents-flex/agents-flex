# Chain

The execution chain is a chain formed by arranging and combining multiple agents. A powerful AI functionality often requires the collaboration of multiple agents.

In the chain execution, it is generally executed by the first agent, and the result of the execution is handed over to the second agent for further cooperation, and then to the third agent, and so on, until we get the desired result.

During the execution of the execution chain, it may be necessary to pause for user interaction or data input, before the execution chain can resume execution. Therefore, each Chain execution chain has many different states, with the following state constants:

```java
public enum ChainStatus {

    READY(0), // Not started execution
    START(1), // Execution started, in progress...
    PAUSE_FOR_WAKE_UP(5), //Paused waiting for wake-up
    PAUSE_FOR_INPUT(6), //Paused waiting for data input
    ERROR(7), //Error occurred
    FINISHED_NORMAL(10), //Successfully completed
    FINISHED_ABNORMAL(11), //Abnormally terminated
    ;

    final int value;

    ChainStatus(int value) {
        this.value = value;
    }
}
```
## Types of Execution Chains

To meet different scenarios, Agents-Flex provides the following types of execution chains:

- SequentialChain: Sequential execution chain
- ParallelChain: Concurrent (parallel) execution chain
- LoopChain: Looping execution chain, which can be used for games between two (or more) models (such as two large models playing chess) or similar functionalities like the `Stanford AI Town`.


## Perception

During the execution of a chain, notifications of execution events are issued at different stages. Additionally, each agent may also publish its own custom events during execution.

Each agent can perceive changes in the execution of the chain by implementing `ChainEventListener`, thereby endowing the agent with the ability to perceive the world.

## Example Code

**Example Code1**：Execute through `SequentialChain` and obtain a result:

```java
public static void main(String[] args) {
    SequentialChain ioChain1 = new SequentialChain();
    ioChain1.addNode(new Agent1("agent1"));
    ioChain1.addNode(new Agent2("agent2"));

    SequentialChain ioChain2 = new SequentialChain();
    ioChain2.addNode(new Agent1("agent3"));
    ioChain2.addNode(new Agent2("agent4"));
    ioChain2.addNode(ioChain1);

    ioChain2.registerEventListener(new ChainEventListener() {
        @Override
        public void onEvent(ChainEvent event, Chain chain) {
            System.out.println(event);
        }
    });


    Object result = ioChain2.executeForResult("your params");
    System.out.println(result);
}
```

The above code implements the Agents arrangement shown in the diagram below:

![](../../assets/images/chians-01.png)


---

**Example Code2** ： The execution chain pauses during execution, waiting for user input before resuming execution.

```java
public static void main(String[] args) {

    SimpleAgent1 agent1 = new SimpleAgent1();
    SimpleAgent2 agent2 = new SimpleAgent2();

    Chain chain = new SequentialChain(agent1, agent2);
    chain.registerInputListener((chain1, parameters) -> {
        Parameter parameter = parameters.get(0);
        System.out.println("Please enter " + parameter.getName());

        Scanner scanner = new Scanner(System.in);
        String userInput = scanner.nextLine();

        Map<String, Object> variables = new HashMap<>();
        variables.put(parameter.getName(), userInput);

        //Resume the execution of the chain.
        chain.resume(variables);
    });

    //Start execution.
    chain.execute(new HashMap<>());

    //Output results (multiple results).
    for (Map.Entry<String, Object> entry : chain.getMemory().getAll().entrySet()) {
        System.out.println("Execution result" + entry);
    }
}
```
