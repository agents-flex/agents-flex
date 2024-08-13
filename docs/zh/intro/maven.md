# Maven 依赖

## 前言

以下的 xml maven 依赖示例中，可能并非最新的 Agents-Flex 版本，请自行查看最新版本，并修改版本号。最新版本查看地址：https://search.maven.org/artifact/com.agentsflex/parent

## 未使用 SpringBoot 的场景

```xml
<dependency>
    <groupId>com.agentsflex</groupId>
    <artifactId>agents-flex-bom</artifactId>
    <version>1.0.0-beta.9</version>
</dependency>
```

## 使用了 SpringBoot 的场景

```xml
<dependency>
    <groupId>com.agentsflex</groupId>
    <artifactId>agents-flex-spring-boot-starter</artifactId>
    <version>1.0.0-beta.9</version>
</dependency>
```
