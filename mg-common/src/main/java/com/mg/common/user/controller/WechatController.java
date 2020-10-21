
package com.mg.common.user.controller;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.mg.common.utils.HttpClientUtil;
import com.mg.framework.sys.PropertyConfigurer;
import com.mg.framework.utils.JsonResponse;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseBody;

import javax.servlet.http.HttpServletRequest;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Controller
@RequestMapping(value = "/wechat",
        produces = "application/json; charset=UTF-8")
public class WechatController {

    @Autowired
    private HttpServletRequest req;

    private  String getTicket(String access_token) {

        String url = "https://api.weixin.qq.com/cgi-bin/ticket/getticket?access_token="+ access_token +"&type=jsapi";

        String json = HttpClientUtil.sendGetRequest(url);
        System.out.println("getTicket返回："+json);
        JSONObject jsonObject = JSON.parseObject(json);
        String errcode = jsonObject.getString("errcode");
        if ("0".equals(errcode)) {
            String ticket = jsonObject.getString("ticket");
            return ticket;
        }

        return null;
    }

    private String SHA1(String decript) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            digest.update(decript.getBytes());
            byte messageDigest[] = digest.digest();
            // Create Hex String
            StringBuffer hexString = new StringBuffer();
            // 字节数组转换为 十六进制 数
            for (int i = 0; i < messageDigest.length; i++) {
                String shaHex = Integer.toHexString(messageDigest[i] & 0xFF);
                if (shaHex.length() < 2) {
                    hexString.append(0);
                }
                hexString.append(shaHex);
            }
            return hexString.toString();

        } catch (NoSuchAlgorithmException e) {
            e.printStackTrace();
        }
        return "";
    }

    @ResponseBody
    @RequestMapping("/config")
    public String config() {
        String grant_type = req.getParameter("grant_type");
        String appid = req.getParameter("appid");
        String secret = req.getParameter("secret");
        if(StringUtils.isBlank(appid)){
            appid = PropertyConfigurer.getConfig("weixin.appid");
        }
        if(StringUtils.isBlank(secret)) {
            secret = PropertyConfigurer.getConfig("weixin.secret");
        }
        String url = "https://api.weixin.qq.com/cgi-bin/token?grant_type="+grant_type+"&appid="+appid+"&secret="+secret;

        String json = HttpClientUtil.sendGetRequest(url);
        //获取AccessToken
        String accessToken = "";
        JSONObject jsonObject = JSON.parseObject(json);
        String errcode = jsonObject.getString("errcode");
        if (StringUtils.isBlank(errcode)) {
            accessToken = jsonObject.getString("access_token");
        }
        //获取Ticket
        String ticket = getTicket(accessToken);

        //时间戳和随机字符串
        String nonceStr = UUID.randomUUID().toString().replace("-", "").substring(0, 16);//随机字符串
        String timestamp = String.valueOf(System.currentTimeMillis() / 1000);//时间戳

        System.out.println("accessToken:"+accessToken+"\njsapi_ticket:"+ticket+"\n时间戳："+timestamp+"\n随机字符串："+nonceStr);

        //将参数排序并拼接字符串
        String str = "jsapi_ticket="+ticket+"&noncestr="+nonceStr+"&timestamp="+timestamp+"&url="+url;

        //将字符串进行sha1加密
        String signature =SHA1(str);
        System.out.println("参数："+str+"\n签名："+signature);

        Map<String,String> map = new HashMap<>();
        map.put("appId",appid);
        map.put("timestamp",timestamp);
        map.put("accessToken",accessToken);
        map.put("ticket",ticket);
        map.put("nonceStr",nonceStr);
        map.put("signature",signature);

        return JsonResponse.success(map, null);
    }
}
