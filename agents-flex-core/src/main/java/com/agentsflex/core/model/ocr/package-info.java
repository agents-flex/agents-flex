/*
 *  Copyright (c) 2023-2026, Agents-Flex (fuhai999@gmail.com).
 *  <p>
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *  <p>
 *  http://www.apache.org/licenses/LICENSE-2.0
 *  <p>
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
/**
 * OCR 模态的供应商无关抽象。
 *
 * <p>本包定义 OCR 请求、响应、统一任务状态及异步查询接口。具体供应商适配器位于
 * {@code agents-flex-ocr-*} 模块中；业务代码应优先依赖本包接口，避免与供应商协议耦合。</p>
 */
package com.agentsflex.core.model.ocr;
