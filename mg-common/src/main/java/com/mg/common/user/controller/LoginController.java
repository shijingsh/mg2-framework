package com.mg.common.user.controller;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.mg.common.entity.InstanceEntity;
import com.mg.common.shiro.service.UserRealm;
import com.mg.common.user.service.UserService;
import com.mg.common.entity.UserEntity;
import com.mg.common.instance.service.InstanceService;
import com.mg.common.user.vo.PhoneDecryptInfo;
import com.mg.common.user.vo.ThirdUserVo;
import com.mg.common.utils.AESGetPhoneNumber;
import com.mg.common.utils.HttpClientUtil;
import com.mg.common.utils.MD5;
import com.mg.framework.log.Constants;
import com.mg.framework.sys.PropertyConfigurer;
import com.mg.framework.utils.WebUtil;
import com.mg.framework.utils.JsonResponse;
import com.mg.framework.utils.UserHolder;
import org.apache.commons.lang3.StringUtils;
import org.apache.shiro.SecurityUtils;
import org.apache.shiro.authc.UsernamePasswordToken;
import org.apache.shiro.mgt.RealmSecurityManager;
import org.apache.shiro.subject.Subject;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.ResponseBody;

import javax.servlet.http.HttpServletRequest;
import java.util.Date;

/**
 * 用户登录/退出
 */
@Controller
@RequestMapping(value = "/",
        produces = "application/json; charset=UTF-8")
public class LoginController {
    @Autowired
    private HttpServletRequest req;
    @Autowired
    private UserService userService;
    @Autowired
    private InstanceService instanceService;

    /**
     * 清理权限缓存
     */
    @RequestMapping("/clearAuth")
    @ResponseBody
    public boolean clearCachedAuthorization() {
        RealmSecurityManager rsm = (RealmSecurityManager)SecurityUtils.getSecurityManager();
        UserRealm realm = (UserRealm)rsm.getRealms().iterator().next();
        realm.clearCachedAuthorization();
        return true;
    }

    @ResponseBody
    @RequestMapping("/login")
    public String login() {

        String jsonString = WebUtil.getJsonBody(req);
        UserEntity userEntity = JSON.parseObject(jsonString, UserEntity.class);
        if (StringUtils.isBlank(userEntity.getLoginName()) || StringUtils.isBlank(userEntity.getPassword())) {
            return JsonResponse.error(100000, "用户名,密码不能为空。");
        }

        Subject subject = SecurityUtils.getSubject();
        //判断是否启用多实例
        String userToken = getInstanceUserToken(userEntity);
        subject.getSession().setAttribute(Constants.TENANT_ID, null);
        //切换数据库到默认实例
        InstanceEntity instanceEntity = null;
        if (StringUtils.isNotBlank(userToken)) {
            instanceEntity = instanceService.findInstanceByToken(userToken);
        }
        if (instanceEntity != null) {
            subject.getSession().setAttribute(Constants.TENANT_ID, instanceEntity.getId());
        }
        try {
            UsernamePasswordToken token = new UsernamePasswordToken(userEntity.getLoginName(), MD5.GetMD5Code(userEntity.getPassword()));
            subject.login(token);
        } catch (Exception e) {
            e.printStackTrace();
            return JsonResponse.error(100000, e.getMessage());
        }
        UserEntity user = userService.getUserById(UserHolder.getLoginUserId());
        userService.updateUserLastLoginDate(user);

        return JsonResponse.success(user, null);
    }

    @ResponseBody
    @RequestMapping("/loginThird")
    public String loginThird() {

        String jsonString = WebUtil.getJsonBody(req);
        ThirdUserVo thirdUserVo = JSON.parseObject(jsonString, ThirdUserVo.class);
        if (StringUtils.isBlank(thirdUserVo.getUserId()) || StringUtils.isBlank(thirdUserVo.getAccessToken())) {
            return JsonResponse.error(100000, "没有第三方授权信息。");
        }


        Subject subject = SecurityUtils.getSubject();
        //判断是否启用多实例
        String userToken = thirdUserVo.getUserToken();
        subject.getSession().setAttribute(Constants.TENANT_ID, null);
        //切换数据库到默认实例
        InstanceEntity instanceEntity = null;
        if (StringUtils.isNotBlank(userToken)) {
            instanceEntity = instanceService.findInstanceByToken(userToken);
        }
        if (instanceEntity != null) {
            subject.getSession().setAttribute(Constants.TENANT_ID, instanceEntity.getId());
        }
        try {
            UserEntity userEntity = userService.saveOrGetThirdUser(thirdUserVo);
            UsernamePasswordToken token = new UsernamePasswordToken(userEntity.getLoginName(), userEntity.getPassword());
            subject.login(token);
        } catch (Exception e) {
            e.printStackTrace();
            return JsonResponse.error(100000, e.getMessage());
        }
        UserEntity user = userService.getUserById(UserHolder.getLoginUserId());
        userService.updateUserLastLoginDate(user);

        return JsonResponse.success(user, null);
    }

    /**
     * 获取公司实例
     *
     * @param userEntity
     * @return
     */
    protected String getInstanceUserToken(UserEntity userEntity) {
        if (StringUtils.isNotBlank(userEntity.getUserToken())) {
            return userEntity.getUserToken();
        }

        return null;
    }

    /**
     * 退出
     */
    @ResponseBody
    @RequestMapping("/loginOut")
    public String loginOut() {
        Subject subject = SecurityUtils.getSubject();
        subject.logout();
        return JsonResponse.success();
    }

    @ResponseBody
    @RequestMapping("/weixinLogin")
    public String weixinLogin() {

        String code = req.getParameter("code");
        String userToken = req.getParameter("userToken");

        String appid = req.getParameter("appid");
        String secret = req.getParameter("secret");

        if (StringUtils.isNotBlank(code)){
            if(StringUtils.isBlank(appid)){
                appid = PropertyConfigurer.getConfig("weixin.appid");
            }
            if(StringUtils.isBlank(secret)) {
                secret = PropertyConfigurer.getConfig("weixin.secret");
            }
            String url = "https://api.weixin.qq.com/sns/jscode2session?appid="+appid+"&secret="+secret+"&js_code=" + code + "&grant_type=authorization_code";

            String json = HttpClientUtil.sendGetRequest(url);
            System.out.println("weixinLogin返回：");
            System.out.println(json);
            JSONObject jsonObject = JSON.parseObject(json);
            String errcode = jsonObject.getString("errcode");
            if (StringUtils.isBlank(errcode)) {
                Subject subject = SecurityUtils.getSubject();
                //判断是否启用多实例
                subject.getSession().setAttribute(Constants.TENANT_ID, null);
                //切换数据库到默认实例
                InstanceEntity instanceEntity = null;
                if (StringUtils.isNotBlank(userToken)) {
                    instanceEntity = instanceService.findInstanceByToken(userToken);
                }
                if (instanceEntity != null) {
                    subject.getSession().setAttribute(Constants.TENANT_ID, instanceEntity.getId());
                }
                try {
                    String userId = jsonObject.getString("unionid");
                    if (StringUtils.isBlank(userId)){
                        userId =  jsonObject.getString("openid");
                    }
                    String sessionKey = jsonObject.getString("session_key");
                    ThirdUserVo thirdUserVo = new ThirdUserVo();
                    thirdUserVo.setUserId(userId);
                    thirdUserVo.setAccessToken(sessionKey);
                    UserEntity userEntity = userService.saveOrGetThirdUser(thirdUserVo);
                    UsernamePasswordToken token = new UsernamePasswordToken(userEntity.getLoginName(), userEntity.getPassword());
                    subject.login(token);
                } catch (Exception e) {
                    e.printStackTrace();
                    return JsonResponse.error(100000, e.getMessage());
                }
                UserEntity user = userService.getUserById(UserHolder.getLoginUserId());
                userService.updateUserLastLoginDate(user);

                return JsonResponse.success(user, null);
            }
        }

        return JsonResponse.success(null, null);
    }


    @ResponseBody
    @RequestMapping("/weixinToken")
    public String weixinToken() {

        String grant_type = req.getParameter("grant_type");

        if (StringUtils.isNotBlank(grant_type)){
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
            JSONObject jsonObject = JSON.parseObject(json);
            String errcode = jsonObject.getString("errcode");
            if (StringUtils.isBlank(errcode)) {
                String access_token = jsonObject.getString("access_token");
                return JsonResponse.success(access_token, null);
            }
        }

        return JsonResponse.success(null, null);
    }

    /**
     * 解密并且获取用户手机号码
     */
    @ResponseBody
    @RequestMapping("/deciphering")
    public  String deciphering(String encryptedData,
                                            String iv, String sessionKey,
                                            HttpServletRequest request) {

        System.out.println("加密的敏感数据:" + encryptedData);
        System.out.println("初始向量:" + iv);
        System.out.println("会话密钥:" + sessionKey);
        //String appId = "XXXXXXXXX";
        AESGetPhoneNumber aes = new AESGetPhoneNumber(encryptedData,sessionKey,iv);
        PhoneDecryptInfo info = aes.decrypt();
        if (null==info){
            System.out.println("error");
        }else {
            System.out.println(info.toString());
            //if (!info.getWatermark().getAppId().equals(appId)){
            //    System.out.println("wrong appId");
            //}
        }

        if(StringUtils.isNotBlank(info.getPhoneNumber())){
            UserEntity user = userService.getUserByRequest(request);
            user.setMobile(info.getPhoneNumber());
            user.setLastLoginDate(new Date());
            userService.updateUser(user);
        }


        return JsonResponse.success(info, null);
    }
}
