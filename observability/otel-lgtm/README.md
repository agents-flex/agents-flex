# Agents-Flex OpenTelemetry LGTM Demo

该目录用一个 Docker 容器启动 OpenTelemetry Collector、Prometheus、Tempo 和 Grafana，
并预置 Agents-Flex 指标面板。

## 1. 启动观测应用

```bash
docker compose -f observability/otel-lgtm/docker-compose.yml up -d
```

Grafana: <http://127.0.0.1:3000>，默认账号 `admin` / `admin`。

## 2. 编译并运行真实 DeepSeek Agent

```bash
mvn -pl demos/agent-demo -am install -DskipTests
set -a
source .env
set +a
mvn -f demos/agent-demo/pom.xml exec:java \
  -Dexec.args=deepseek-otel \
  -Dexec.cleanupDaemonThreads=false
```

也可以显式覆盖默认值：

```bash
export DEEPSEEK_MODEL=deepseek-chat
export DEEPSEEK_ENDPOINT=https://api.deepseek.com
export MAVEN_OPTS="-Dotel.exporter.otlp.endpoint=http://127.0.0.1:4317"
```

## 3. 查看数据

- 首页会打开 `Agents-Flex / Agents-Flex DeepSeek Agent Observability` 面板；
- 面板包含 Prompt Cache read/miss Token、Token 命中率、请求结果分布和 Agent 侧 Token 用量；
- `Explore -> Tempo` 可查看 `agent.turn`、`agent.model`、`agent.tool`、`deepseek.chat` 和 `http.client.request` Span；
- `Explore -> Prometheus` 可查看 `agentsflex_*`、`gen_ai_*` 和 `http_client_*` Metrics。

停止容器：

```bash
docker compose -f observability/otel-lgtm/docker-compose.yml down
```
