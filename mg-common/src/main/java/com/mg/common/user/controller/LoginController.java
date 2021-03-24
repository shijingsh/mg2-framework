package com.mg.common.user.controller;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.mg.common.components.SmsService;
import com.mg.common.entity.InstanceEntity;
import com.mg.common.shiro.service.UserRealm;
import com.mg.common.user.service.UserService;
import com.mg.common.entity.UserEntity;
import com.mg.common.instance.service.InstanceService;
import com.mg.common.user.vo.PhoneDecryptInfo;
import com.mg.common.user.vo.ThirdLoginVo;
import com.mg.common.user.vo.ThirdUserVo;
import com.mg.common.utils.AESGetPhoneNumber;
import com.mg.common.utils.HttpClientUtil;
import com.mg.common.utils.JsonUtils;
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
    @Autowired
    private SmsService smsService;
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
        UserEntity user = userService.getUser(userEntity.getLoginName());
        if(user!=null){
            user.setLastLoginPlatform(userEntity.getLastLoginPlatform());
            userService.updateUserLastLoginDate(user);
        }

        return JsonResponse.success(user, null);
    }

    /**
     * 微信授权登陆APP
     * apple 授权登陆APP
     * @return
     */
    @ResponseBody
    @RequestMapping("/loginThird")
    public String loginThird() {

        String jsonString = WebUtil.getJsonBody(req);
        ThirdUserVo thirdUserVo = JSON.parseObject(jsonString, ThirdUserVo.class);
        System.out.println("loginThird登录中：");
        System.out.println(JsonUtils.toJsonStr(thirdUserVo));
        if ((StringUtils.isBlank(thirdUserVo.getUnionId()) && StringUtils.isBlank(thirdUserVo.getAppleId()) )
                || StringUtils.isBlank(thirdUserVo.getAccessToken())) {
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
        UserEntity userEntity = null;
        try {
            if (StringUtils.isNotBlank(thirdUserVo.getMobile())) {
                UserEntity mobileUser = userService.getUserByMobile(thirdUserVo.getMobile());

                String code = thirdUserVo.getVerifyCode();
                if (StringUtils.isBlank(code)) {
                    return JsonResponse.error(100002, "验证码不能为空。");
                }
                if(smsService.validateCode(thirdUserVo.getMobile(),code)){
                    if (mobileUser!=null) {
                        //直接关联已经存在的用户
                        userEntity = userService.saveThirdUser(thirdUserVo,mobileUser);
                    }else{
                        userEntity = userService.saveThirdUser(thirdUserVo);
                    }
                }else{
                    return JsonResponse.error(100002, "验证码输入错误");
                }
            }else {
                userEntity = userService.getThirdUser(thirdUserVo);
                if(userEntity==null || StringUtils.isBlank(userEntity.getMobile())){
                    return JsonResponse.error(100001, "手机号码不能为空。");
                }
            }

            UsernamePasswordToken token = new UsernamePasswordToken(userEntity.getLoginName(), userEntity.getPassword());
            subject.login(token);
        } catch (Exception e) {
            e.printStackTrace();
            return JsonResponse.error(100000, e.getMessage());
        }
        if(userEntity!=null){
            userEntity.setLastLoginPlatform(thirdUserVo.getLastLoginPlatform());
            userService.updateUserLastLoginDate(userEntity);
        }

        return JsonResponse.success(userEntity, null);
    }


    /**
     * 小程序登陆
     * @return
     */
    @ResponseBody
    @RequestMapping("/weixinLogin")
    public String weixinLogin() {

        ThirdLoginVo loginVo = WebUtil.getJsonBody(req, ThirdLoginVo.class);

        System.out.println("weixinLogin登录中：");
        System.out.println(JsonUtils.toJsonStr(loginVo));
        String code = loginVo.getCode();
        String userToken = loginVo.getUserToken();
        String appid = loginVo.getAppid();
        String secret = loginVo.getSecret();
        String nickName = loginVo.getNickName();
        String avatarUrl = loginVo.getAvatarUrl();
        String gender = loginVo.getGender();
        String email = loginVo.getEmail();
        if (StringUtils.isBlank(code)) {
            return JsonResponse.error(100000, "微信code不能为空。");
        }

        if (StringUtils.isNotBlank(code)){
            if(StringUtils.isBlank(appid)){
                appid = PropertyConfigurer.getConfig("weixin.appid");
            }
            if(StringUtils.isBlank(secret)) {
                secret = PropertyConfigurer.getConfig("weixin.secret");
            }
            String url = "https://api.weixin.qq.com/sns/jscode2session?appid="+appid+"&secret="+secret+"&js_code=" + code + "&grant_type=authorization_code";

            String json = HttpClientUtil.sendGetRequest(url);
            System.out.println("jscode2session返回：");
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
                UserEntity userEntity = null;
                try {
                    String unionId = jsonObject.getString("unionid");
                    System.out.println("weixinLogin返回unionid："+unionId);
                    if (StringUtils.isBlank(unionId)){
                        unionId =  jsonObject.getString("openid");
                        System.out.println("weixinLogin返回openid："+unionId);
                    }

                    String mobile = null;
                    String sessionKey = jsonObject.getString("session_key");
                    if(StringUtils.isNotBlank(loginVo.getEncryptedData()) && StringUtils.isNotBlank(loginVo.getIv())){
                        AESGetPhoneNumber aes = new AESGetPhoneNumber(loginVo.getEncryptedData(),sessionKey,loginVo.getIv());
                        PhoneDecryptInfo info = aes.decrypt();
                        if (null==info){
                            System.out.println("error");
                            return JsonResponse.error(100003, "解密微信手机号发生错误，会话超时。");
                        }else {
                            System.out.println("======================解密微信手机号========================");
                            System.out.println(JsonUtils.toJsonStr(info));
                        }
                        if(info!=null && StringUtils.isNotBlank(info.getPhoneNumber())){
                            mobile = info.getPhoneNumber();

                        }
                    }

                    ThirdUserVo thirdUserVo = new ThirdUserVo();
                    thirdUserVo.setUnionId(unionId);
                    thirdUserVo.setLoginName(mobile);
                    thirdUserVo.setAccessToken(sessionKey);
                    thirdUserVo.setUserAvatar(avatarUrl);
                    thirdUserVo.setUserName(nickName);
                    thirdUserVo.setUserGender(gender);
                    thirdUserVo.setMobile(mobile);
                    thirdUserVo.setEmail(email);

                    if(StringUtils.isNotBlank(mobile)){
                        UserEntity mobileUser = userService.getUserByMobile(mobile);
                        if (mobileUser!=null) {
                            //直接关联已经存在的用户
                            userEntity = userService.saveThirdUser(thirdUserVo,mobileUser);
                        }else{
                            userEntity = userService.getUserByUnionId(unionId);
                            if (userEntity==null || StringUtils.isBlank(mobile)) {
                                return JsonResponse.error(100001, "手机号码不能为空。");
                            }
                            userEntity = userService.saveThirdUser(thirdUserVo);
                        }
                    }else{
                        userEntity = userService.getUserByUnionId(unionId);
                        if (userEntity==null || StringUtils.isBlank(userEntity.getMobile())) {
                            return JsonResponse.error(100001, "手机号码不能为空。");
                        }
                        userEntity = userService.saveThirdUser(thirdUserVo);
                    }


                    UsernamePasswordToken token = new UsernamePasswordToken(userEntity.getLoginName(), userEntity.getPassword());
                    subject.login(token);
                } catch (Exception e) {
                    e.printStackTrace();
                    return JsonResponse.error(100000, e.getMessage());
                }
                if(userEntity!=null){
                    userEntity.setLastLoginPlatform(loginVo.getLastLoginPlatform());
                    userService.updateUserLastLoginDate(userEntity);
                }

                return JsonResponse.success(userEntity, null);
            }
        }

        return JsonResponse.success(null, null);
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

        if(info!=null && StringUtils.isNotBlank(info.getPhoneNumber())){
            UserEntity user = userService.getUserByRequest(request);
            user.setMobile(info.getPhoneNumber());
            user.setLastLoginDate(new Date());
            userService.updateUser(user);
        }


        return JsonResponse.success(info, null);
    }


    @ResponseBody
    @RequestMapping("/bindingMobile")
    public String bindingMobile() {

        String jsonString = WebUtil.getJsonBody(req);
        UserEntity userEntity = JSON.parseObject(jsonString, UserEntity.class);
        if (StringUtils.isBlank(userEntity.getLoginName())) {
            return JsonResponse.error(100000, "用户名不能为空。");
        }
        if (StringUtils.isBlank(userEntity.getMobile())) {
            return JsonResponse.error(100001, "手机号码不能为空。");
        }
        if (StringUtils.isBlank(userEntity.getVerifyCode())) {
            return JsonResponse.error(100002, "验证码不能为空。");
        }
        UserEntity user = userService.getUser(userEntity.getLoginName());
        if (user == null) {
            return JsonResponse.error(100003, "用户尚未注册");
        }
        UserEntity userMobile = userService.getUserByMobile(userEntity.getMobile());
        if (userMobile != null) {
            return JsonResponse.error(100003, "手机号码已被其他用户占用，请更换");
        }
        String code = userEntity.getVerifyCode();
        if(smsService.validateCode(userEntity.getMobile(),code)){
            user.setMobile(userEntity.getMobile());
            if(StringUtils.isBlank(user.getLoginName())){
                user.setLoginName(userEntity.getMobile());
            }
            if(StringUtils.isBlank(user.getName())){
                user.setName(userEntity.getMobile());
            }
            userService.updateUser(user);
        }else{
            return JsonResponse.error(100004, "验证码输入错误");
        }
        return JsonResponse.success(user);
    }

}
