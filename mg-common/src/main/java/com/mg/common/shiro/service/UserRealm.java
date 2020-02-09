package com.mg.common.shiro.service;

import com.mg.common.entity.UserEntity;
import com.mg.common.shiro.util.AdminPermission;
import com.mg.common.shiro.util.AuthorizationHelper;
import com.mg.common.shiro.util.WildcardPermissionExResolver;
import com.mg.common.user.service.UserService;
import com.mg.framework.log.Constants;
import com.mg.framework.utils.StatusEnum;
import com.mg.framework.utils.UserHolder;
import org.apache.commons.lang.StringUtils;
import org.apache.shiro.SecurityUtils;
import org.apache.shiro.authc.*;
import org.apache.shiro.authz.AuthorizationInfo;
import org.apache.shiro.authz.SimpleAuthorizationInfo;
import org.apache.shiro.realm.AuthorizingRealm;
import org.apache.shiro.session.Session;
import org.apache.shiro.subject.PrincipalCollection;
import org.apache.shiro.subject.SimplePrincipalCollection;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class UserRealm extends AuthorizingRealm {

    @Autowired
    private UserService userService;

    public UserRealm() {
        super();
        setName("userRealm");

        setAuthenticationCacheName(AuthorizationHelper.SHIRO_CACHE_NAME);

        //处理权限比较方法, 自定义比较方法
        setPermissionResolver(new WildcardPermissionExResolver());
    }

    /**
     * 授权
     * @param principals
     * @return
     */
    protected AuthorizationInfo doGetAuthorizationInfo(PrincipalCollection principals) {
        String username = (String) principals.getPrimaryPrincipal();
        String[] names = StringUtils.split(username, ":");

        String loginUserName = names[0];
        String companyInstanceName = null;
        if(names.length>1){
            companyInstanceName = names[1];
        }

        return getSimpleAuthorizationInfo(loginUserName, companyInstanceName);
    }

    /**
     * 身份认证
     * @param token
     * @return
     * @throws AuthenticationException
     */
    @Override
    protected AuthenticationInfo doGetAuthenticationInfo(AuthenticationToken token) throws AuthenticationException {
        String username = (String)token.getPrincipal();  //得到用户名
        String password = new String((char[])token.getCredentials()); //得到密码
        UserEntity user = userService.getUser(username,password);

        if (user == null) {
            throw new UnknownAccountException("用户名或是密码错误");
        }
        if(user.getStatus() == StatusEnum.STATUS_INVALID) {
            throw new LockedAccountException("账号已失效，请联系管理员。");
        }
        Session session = SecurityUtils.getSubject().getSession();
        session.setAttribute(Constants.CURRENT_USER, user);

        SimpleAuthenticationInfo authenticationInfo = new SimpleAuthenticationInfo(username, user.getPassword().toCharArray(), getName());
        //清空集合和清空授权, 防止用户非法退出登录,
        //而保存本地的cache尚未清空, 导致下次同用户无法登录查看权限
        clearCachedAuthorizationInfo(authenticationInfo.getPrincipals());

        //清除掉之前的权限信息以便重新加载
        this.clearCachedAuthorizationInfo(new SimplePrincipalCollection(username, "userRealm"));

        return authenticationInfo;
    }

    private SimpleAuthorizationInfo getSimpleAuthorizationInfo(String loginUserName, String companyInstanceName) {
        UserEntity user = UserHolder.getLoginUser();
        if (user == null) {
            return null;
        }
        SimpleAuthorizationInfo info = new SimpleAuthorizationInfo();

        if(user.isAdmin()){
            AdminPermission adminPermission = new AdminPermission();
            info.addObjectPermission(adminPermission);
            return info;
        }

        return info;
    }
}
