<h4 align="right"><a href="./readme.md">English</a> | <a href="./readme_zh.md">简体中文</a> | <strong>日本語</strong></h4>

<p align="center">
    <img src="./docs/assets/images/banner.png"/>
</p>


# Agents-Flexは、JavaベースのLangChainのようなLLMアプリケーションフレームワークです。

---

## 機能

- LLMアクセス
- プロンプト、プロンプトテンプレート
- 関数呼び出しの定義、呼び出し、実行
- メモリ
- 埋め込み
- ベクトルストア
- リソースローダー
- ドキュメント
  - 分割器
  - ローダー
  - パーサー
    - PoiParser
    - PdfBoxParser
- エージェント
  - LLMエージェント
- チェーン
  - シーケンシャルチェーン
  - パラレルチェーン
  - ループチェーン
  - チェーンノード
    - エージェントノード
    - エンドノード
    - ルーターノード
      - GroovyRouterNode
      - QLExpressRouterNode
      - LLMRouterNode

## 簡単なチャット

OpenAI LLMを使用:

```java
 @Test
public void testChat() {
    OpenAILlmConfig config = new OpenAILlmConfig();
    config.setApiKey("sk-rts5NF6n*******");

    Llm llm = new OpenAILlm(config);
    String response = llm.chat("あなたの名前は何ですか？");

    System.out.println(response);
}
```


Qwen LLMを使用:

```java
 @Test
public void testChat() {
    QwenLlmConfig config = new QwenLlmConfig();
    config.setApiKey("sk-28a6be3236****");
    config.setModel("qwen-turbo");

    Llm llm = new QwenLlm(config);
    String response = llm.chat("あなたの名前は何ですか？");

    System.out.println(response);
}
```


SparkAi LLMを使用:

```java
 @Test
public void testChat() {
    SparkLlmConfig config = new SparkLlmConfig();
    config.setAppId("****");
    config.setApiKey("****");
    config.setApiSecret("****");

    Llm llm = new SparkLlm(config);
    String response = llm.chat("あなたの名前は何ですか？");

    System.out.println(response);
}
```

## 履歴付きチャット


```java
public static void main(String[] args) {
    SparkLlmConfig config = new SparkLlmConfig();
    config.setAppId("****");
    config.setApiKey("****");
    config.setApiSecret("****");

    Llm llm = new SparkLlm(config);

    HistoriesPrompt prompt = new HistoriesPrompt();

    System.out.println("何か質問してください...");
    Scanner scanner = new Scanner(System.in);
    String userInput = scanner.nextLine();

    while (userInput != null) {

        prompt.addMessage(new HumanMessage(userInput));

        llm.chatStream(prompt, (context, response) -> {
            System.out.println(">>>> " + response.getMessage().getContent());
        });

        userInput = scanner.nextLine();
    }
}
```

## 関数呼び出し

- ステップ1: ネイティブ関数を定義

```java
public class WeatherUtil {

    @FunctionDef(name = "get_the_weather_info", description = "get the weather info")
    public static String getWeatherInfo(
        @FunctionParam(name = "city", description = "the city name") String name
    ) {
        //ここで天気情報のためにサードパーティAPIを呼び出す必要があります
        return name + "の天気は曇り時々晴れです。";
    }
}

```

- ステップ2: LLMから関数を呼び出す

```java
 public static void main(String[] args) {
    OpenAILlmConfig config = new OpenAILlmConfig();
    config.setApiKey("sk-rts5NF6n*******");

    OpenAILlm llm = new OpenAILlm(config);

    FunctionPrompt prompt = new FunctionPrompt("今日の北京の天気はどうですか？", WeatherUtil.class);
    FunctionResultResponse response = llm.chat(prompt);

    Object result = response.getFunctionResult();

    System.out.println(result);
    //今日の北京の天気は曇り時々晴れです
}
```


## モジュール

![](./docs/assets/images/modules.jpg)
