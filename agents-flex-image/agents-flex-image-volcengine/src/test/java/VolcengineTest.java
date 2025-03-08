import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.volcengine.service.visual.IVisualService;
import com.volcengine.service.visual.impl.VisualServiceImpl;

public class VolcengineTest {
    //以下是同步调用直接返回结果的方法，按照实际需求可调用sdk同步及异步相关方法；
    public static void main(String[] args) {
        IVisualService visualService = VisualServiceImpl.getInstance();
        // call below method if you dont set ak and sk in ～/.vcloud/config
        visualService.setAccessKey("AKLTNmU0M2RkNWZkMmZmNDQwYWI2NTZiMjA1ODYxY2M3MjE");
        visualService.setSecretKey("TkdObFpXSTFZMlJtWldReE5EVTRPRGt4TkRsaE1EVTRaalpsTnpnMllURQ==");


        JSONObject req=new JSONObject();
        //请求Body(查看接口文档请求参数-请求示例，将请求参数内容复制到此)
        req.put("req_key","high_aes_general_v21_L");
        req.put("prompt","千军万马");
        req.put("model_version","general_v2.1_L");
        req.put("req_schedule_conf","general_v20_9B_pe");
        req.put("llm_seed",-1);
        req.put("seed",-1);
        req.put("scale",3.5);
        req.put("ddim_steps",25);
        req.put("width",512);
        req.put("height",512);
        req.put("use_pre_llm",true);
        req.put("use_sr",true);
        req.put("sr_seed",-1);
        req.put("sr_strength",0.4);
        req.put("sr_scale",3.5);
        req.put("sr_steps",20);
        req.put("is_only_sr",false);
        req.put("return_url",true);
        //创建子级JSONObject
        JSONObject subData = new JSONObject();
        subData.put("add_logo", true);
        subData.put("position", 2);
        subData.put("language", 0);
        subData.put("opacity", 0.3);
        subData.put("logo_text_content", "wangyangyang");
        req.put("logo_info",subData);


        try {
            Object response = visualService.cvProcess(req);
            System.out.println(JSON.toJSONString(response));
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
