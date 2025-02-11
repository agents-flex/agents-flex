# Image generation

In Agents-Flex, the ability to generate images through AI is built-in.

## Large model support
Agents-Flex image generation models support the following:

| Large Language Model Name                  | Support Status | Description |
|--------------------------------------------|--------|-------|
| Openai                                     | ✅ Supported | - |
| Stability                                  | ✅ Supported | - |
| GiteeAI - stable-diffusion-3-medium        | ✅ Supported | - |
| GiteeAI - FLUX.1-schnell                   | ✅ Supported | - |
| GiteeAI - stable-diffusion-xl-base-1.0     | ✅ Supported | - |
| GiteeAI - Kolors                           | ✅ Supported | - |
| SiliconFlow - Flux.1-schnell               | ✅ Supported | - |
| SiliconFlow - Stable Diffusion 3           | ✅ Supported | - |
| SiliconFlow - Stable Diffusion XL          | ✅ Supported | - |
| SiliconFlow - Stable Diffusion 2.1         | ✅ Supported | - |
| SiliconFlow - Stable Diffusion Turbo       | ✅ Supported | - |
| SiliconFlow - Stable Diffusion XL Turbo    | ✅ Supported | - |
| SiliconFlow - Stable Diffusion XL Lighting | ✅ Supported | - |
| More                                       |Planning... | Looking forward to PR |

## Example Code

```java
 @Test
public void testGenImage(){
    GiteeImageModelConfig config = new GiteeImageModelConfig();
    config.setApiKey("****");

    //Step 1: Create an ImageModel
    ImageModel imageModel = new GiteeImageModel(config);

    //Step 2: Create pictures to generate prompt words and parameters
    GenerateImageRequest request = new GenerateImageRequest();
    request.setPrompt("A cute little tiger standing in the high-speed train");
    request.setSize(1024, 1024);

    //Step 3: Generate images through large models
    ImageResponse generate = imageModel.generate(request);
    System.out.println(generate);

    int index = 0;
    for (Image image : generate.getImages()) {
        //Step 4: Save the image locally
        image.writeToFile(new File("/image-path/"+(index++)+".jpg"));
    }
}
```

或者使用 OpenAI ImageModel

```java 5-7
 @Test
public void testGenImage(){

    //Or use OpenAI ImageModel
    OpenAIImageModelConfig config = new OpenAIImageModelConfig();
    config.setApiKey("sk-5gqOclb****");
    ImageModel imageModel = new OpenAIImageModel(config);


    GenerateImageRequest request = new GenerateImageRequest();
    request.setPrompt("A cute little tiger standing in the high-speed train");
    request.setSize(1024, 1024);

    ImageResponse generate = imageModel.generate(request);
    System.out.println(generate);

    int index = 0;
    for (Image image : generate.getImages()) {
        image.writeToFile(new File("/image-path/"+(index++)+".jpg"));
    }
}
```

Or use  SiliconFlowImageModel

```java 5-8
 @Test
public void testGenImage(){

    //Or use  SiliconFlowImageModel
    SiliconflowImageModelConfig config = new SiliconflowImageModelConfig();
    config.setModel(SiliconflowImageModels.Stable_Diffusion_XL);
    config.setApiKey("sk-****");
    ImageModel imageModel = new OpenAIImageModel(config);


    GenerateImageRequest request = new GenerateImageRequest();
    request.setPrompt("A cute little tiger standing in the high-speed train");
    request.setSize(1024, 1024);

    ImageResponse generate = imageModel.generate(request);
    System.out.println(generate);

    int index = 0;
    for (Image image : generate.getImages()) {
        image.writeToFile(new File("/image-path/"+(index++)+".jpg"));
    }
}
```
