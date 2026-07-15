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
package com.agentsflex.core.model.image;

/**
 * 图片模型的统一同步接口。
 * <p>
 * 无论供应商底层使用同步还是异步任务协议，调用方都只通过 {@link #generate(GenerateImageRequest)}
 * 获取最终图片。必须异步提交的供应商应在适配器内部完成轮询，不向公共架构暴露任务状态。
 * </p>
 */
public interface ImageModel {

    /**
     * 根据统一请求生成或编辑图片，并同步返回最终结果。
     * <p>请求中没有输入图片时通常执行文生图；存在输入图片时由适配器和模型决定执行参考生成或编辑。</p>
     *
     * @param request 图片生成请求
     * @return 最终图片或错误信息；具体适配器不应返回异步任务占位结果
     */
    ImageResponse generate(GenerateImageRequest request);

}
