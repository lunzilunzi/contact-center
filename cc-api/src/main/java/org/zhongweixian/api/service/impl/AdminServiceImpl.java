package org.zhongweixian.api.service.impl;

import com.github.pagehelper.PageHelper;
import com.github.pagehelper.PageInfo;
import org.apache.commons.lang3.RandomStringUtils;
import org.apache.commons.lang3.StringUtils;
import org.cti.cc.constant.Constant;
import org.cti.cc.entity.*;
import org.cti.cc.enums.ErrorCode;
import org.cti.cc.mapper.*;
import org.cti.cc.mapper.base.BaseMapper;
import org.cti.cc.po.*;
import org.cti.cc.util.AuthUtil;
import org.cti.cc.util.DateTimeUtil;
import org.cti.cc.util.LicenseUtil;
import org.cti.cc.vo.AdminLogin;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;
import org.zhongweixian.api.exception.BusinessException;
import org.zhongweixian.api.service.AdminService;
import org.zhongweixian.api.util.BcryptUtil;
import org.zhongweixian.api.vo.server.MenuVo;
import org.zhongweixian.api.vo.server.RoleMenuVo;
import org.zhongweixian.api.vo.server.RoleVo;

import java.time.Instant;
import java.util.*;

/**
 * Created by caoliang on 2022/1/7
 */
@Service
public class AdminServiceImpl extends BaseServiceImpl<AdminUser> implements AdminService {

    @Value("${spring.datasource.url:}")
    private String salt;

    @Value("${platform.v9.key:pykqu7qfhcs5gz87}")
    private String key;

    @Autowired
    private AdminMenuMapper adminMenuMapper;

    @Autowired
    private AdminUserMapper adminUserMapper;

    @Autowired
    private AdminRoleMapper adminRoleMapper;

    @Autowired
    private AgentMapper agentMapper;

    @Autowired
    private PlatformLicenseMapper platformLicenseMapper;


    @Override
    public AdminLoginResult login(AdminLogin adminLogin) {
        AdminUser adminUser = adminUserMapper.adminLogin(adminLogin.getUsername());
        if (adminUser == null) {
            logger.warn("user:{} login error", adminLogin.getUsername());
            throw new BusinessException(ErrorCode.ACCOUNT_ERROR);
        }
        if (adminUser.getStatus() == 0) {
            throw new BusinessException(ErrorCode.ACCOUNT_DISABLED);
        }
        //验证密码
        try {
            if (!BcryptUtil.checkPwd(adminLogin.getPasswd(), adminUser.getPasswd())) {
                throw new BusinessException(ErrorCode.ACCOUNT_ERROR);
            }
        } catch (Exception e) {
            logger.error("user:{} password is error", adminLogin.getUsername());
            throw new BusinessException(ErrorCode.ACCOUNT_ERROR);
        }

        CompanyInfo companyInfo = companyMapper.selectById(adminUser.getCompanyId());
        if (companyInfo == null) {
            //企业禁用
            throw new BusinessException(ErrorCode.COMPANY_NOT_AVALIABLE);
        }
        String token = AuthUtil.createToken(adminUser.getUsername(), adminUser.getCompanyId(), companyInfo.getSecretKey());

        AdminLoginResult result = new AdminLoginResult();
        result.setCts(Instant.now().getEpochSecond());
        result.setToken(token);
        UserInfo userInfo = new UserInfo();
        userInfo.setAvatar(adminUser.getAvatar());
        userInfo.setOssServer(ossServer);
        userInfo.setUsername(adminUser.getUsername());
        userInfo.setCompanyName(companyInfo.getName());
        userInfo.setCompanyCode(companyInfo.getCompanyCode());
        userInfo.setGmt(companyInfo.getGmt());
        userInfo.setClientIp(adminLogin.getClientIp());
        result.setUserInfo(userInfo);
        result.setMenus(getMenus(adminUser.getId()));
        redisTemplate.opsForValue().set(Constant.ADMIN_TOKEN + token, result);
        return result;
    }

    @Override
    public Boolean logout(String token) {
        return redisTemplate.delete("token:" + token);
    }

    @Override
    public List<AdminMenu> menusList(Map<String, Object> params) {
        return adminMenuMapper.selectList(params);
    }


    private List<MenusPo> getMenus(Long uid) {
        Map<String, Object> params = new HashMap<>();
        params.put("uid", uid);
        params.put("menuLevel", 1);
        return userMenuIterator(params);
    }

    @Override
    public Integer saveOrUpdateMenus(MenuVo menusVo) {
        Map<String, Object> params = new HashMap<>();
        params.put("name", menusVo.getName());
        List<AdminMenu> menusList = adminMenuMapper.selectList(params);

        AdminMenu adminMenu = new AdminMenu();
        BeanUtils.copyProperties(menusVo, adminMenu);

        if (!CollectionUtils.isEmpty(menusList)) {
            if (menusVo.getMenuId() == null || (menusVo.getMenuId() != null && !menusVo.getMenuId().equals(menusList.get(0).getMenuId()))) {
                throw new BusinessException(ErrorCode.DUPLICATE_EXCEPTION);
            }
        }

        if (StringUtils.isBlank(menusVo.getMenuId())) {
            adminMenu.setMenuId(RandomStringUtils.randomNumeric(32));
            adminMenu.setUts(Instant.now().getEpochSecond());
            adminMenu.setUts(adminMenu.getCts());
            return adminMenuMapper.insertSelective(adminMenu);
        }
        adminMenu.setUts(Instant.now().getEpochSecond());
        return adminMenuMapper.updateByPrimaryKeySelective(adminMenu);
    }

    @Override
    public Integer deleteMenus(String menuId) {
        AdminMenu adminMenu = adminMenuMapper.selectByMenuId(menuId);
        if (adminMenu == null) {
            throw new BusinessException(ErrorCode.DATA_NOT_EXIST);
        }
        if (adminMenu.getChildNum() != null && adminMenu.getChildNum() > 0) {
            throw new BusinessException(ErrorCode.PARAMETER_ERROR, "子节点存在");
        }
        //删除角色绑定的菜单
        adminMenuMapper.deleteRoleMenu(menuId);
        return adminMenuMapper.deleteMenu(menuId);
    }

    @Override
    public RolePo getRoleMenus(Long id) {
        AdminRole adminRole = adminRoleMapper.selectByPrimaryKey(id);
        if (adminRole == null) {
            throw new BusinessException(ErrorCode.DATA_NOT_EXIST);
        }
        RolePo rolePo = new RolePo();
        BeanUtils.copyProperties(adminRole, rolePo);

        //获取所有菜单
        List<MenusPo> adminMenus = getAllMenus(null);

        //获取角色菜单
        List<String> roleMenuIds = adminMenuMapper.selectByRoleId(id);

        //递归遍历
        forEachMenu(roleMenuIds, adminMenus);

        rolePo.setAdminMenuList(adminMenus);
        return rolePo;
    }

    /**
     * 递归遍历菜单
     *
     * @param roleMenuIds
     * @param adminMenus
     */
    private void forEachMenu(List<String> roleMenuIds, List<MenusPo> adminMenus) {
        if (CollectionUtils.isEmpty(adminMenus) || CollectionUtils.isEmpty(roleMenuIds)) {
            return;
        }
        for (MenusPo menusPo : adminMenus) {
            if (roleMenuIds.contains(menusPo.getMenuId())) {
                menusPo.setUid(1L);
            } else {
                menusPo.setUid(0L);
            }
            if (!CollectionUtils.isEmpty(menusPo.getChilds())) {
                forEachMenu(roleMenuIds, menusPo.getChilds());
            }
        }
    }

    /**
     * 获取菜单
     *
     * @param params
     * @return
     */
    private List<MenusPo> getAllMenus(Map<String, Object> params) {
        if (params == null) {
            params = new HashMap<>();
            params.put("menuLevel", 1);
        }
        List<AdminMenu> adminMenus = adminMenuMapper.selectList(params);
        if (CollectionUtils.isEmpty(adminMenus)) {
            return null;
        }
        List<MenusPo> menusPoList = new ArrayList<>();
        for (AdminMenu child : adminMenus) {
            MenusPo childPo = new MenusPo();
            params.put("parentId", child.getMenuId());
            params.remove("menuLevel");
            BeanUtils.copyProperties(child, childPo);
            childPo.setChilds(getAllMenus(params));
            menusPoList.add(childPo);
        }
        return menusPoList;
    }


    @Override
    public PageInfo<AdminRole> getRoleList(Map<String, Object> params) {
        Integer pageNum = (Integer) params.get("pageNum");
        Integer pageSize = (Integer) params.get("pageSize");
        PageHelper.startPage(pageNum, pageSize);
        List<AdminRole> list = adminRoleMapper.selectList(params);
        return new PageInfo<AdminRole>(list);
    }

    @Override
    public Integer saveOrUpdateRole(RoleVo roleVo) {
        Map<String, Object> params = new HashMap<>();
        params.put("companyId", roleVo.getCompanyId());
        params.put("roleName", roleVo.getRoleName());
        List<AdminRole> roleList = adminRoleMapper.selectList(params);
        if (!CollectionUtils.isEmpty(roleList)) {
            if (roleVo.getId() == null || !roleVo.getRoleName().equals(roleList.get(0).getRoleName())) {
                throw new BusinessException(ErrorCode.DUPLICATE_EXCEPTION);
            }
        }
        AdminRole adminRole = new AdminRole();
        BeanUtils.copyProperties(roleVo, adminRole);
        if (roleVo.getId() == null) {
            adminRole.setCts(Instant.now().getEpochSecond());
            adminRole.setUts(adminRole.getCts());
            return adminRoleMapper.insertSelective(adminRole);
        }
        adminRole.setUts(Instant.now().getEpochSecond());
        return adminRoleMapper.updateByPrimaryKeySelective(adminRole);
    }

    @Override
    public Integer deleteRole(Long id) {
        //角色是否存在
        RolePo adminRole = adminRoleMapper.selectRoleMenu(id);
        if (adminRole == null) {
            throw new BusinessException(ErrorCode.DATA_NOT_EXIST);
        }

        //角色是否被管理账号引用
        List<AdminUser> adminUsers = adminUserMapper.selectByRoleId(id);
        if (!CollectionUtils.isEmpty(adminUsers)) {
            throw new BusinessException(ErrorCode.RUNTIME_DATA_EXCEPTION, "角色已经绑定账号");
        }
        //删除角色权限
        adminRoleMapper.deleteMenuByRoleId(id);
        return adminRoleMapper.deleteRole(id);
    }

    @Override
    public Integer roleBindMenu(RoleMenuVo roleMenuVo) {
        //先判断数据是否存在
        RolePo role = adminRoleMapper.selectRoleMenu(roleMenuVo.getRoleId());
        if (role == null) {
            throw new BusinessException(ErrorCode.DATA_NOT_EXIST);
        }

        //先删除角色绑定的所有菜单
        adminRoleMapper.deleteMenuByRoleId(roleMenuVo.getRoleId());
        if (CollectionUtils.isEmpty(roleMenuVo.getMenuIds())) {
            return 0;
        }
        List<AdminRoleMenu> roleMenus = new ArrayList<>();
        for (String menuId : roleMenuVo.getMenuIds()) {
            AdminRoleMenu roleMenu = new AdminRoleMenu();
            roleMenu.setMenuId(menuId);
            roleMenu.setRoleId(roleMenuVo.getRoleId());
            roleMenu.setCts(Instant.now().getEpochSecond());
            roleMenu.setUts(roleMenu.getCts());
            roleMenu.setCompanyId(role.getCompanyId());
            roleMenus.add(roleMenu);
        }
        return adminRoleMapper.batchInserRoleMenus(roleMenus);
    }

    @Override
    public void initLicense() {
        Integer agentNum = agentMapper.agentNum(null);
        //默认30天授权，10坐席
        List<PlatformLicense> licenseList = platformLicenseMapper.selectListByMap(null);
        if (CollectionUtils.isEmpty(licenseList)) {
            if (agentNum > 10) {
                logger.error("license is error and agent num over 10");
                System.exit(0);
            }
            PlatformLicense platformLicense = new PlatformLicense();
            platformLicense.setCts(Instant.now().getEpochSecond());
            platformLicense.setUts(Instant.now().getEpochSecond());
            try {
                String license = LicenseUtil.generateLicense(salt, key, 10, DateTimeUtil.addday(new Date(), 30));
                platformLicense.setPlatformLicense(license);
            } catch (Exception e) {
                logger.error(e.getMessage(), e);
            }
            platformLicenseMapper.insertSelective(platformLicense);
        }
    }

    /**
     * 登录账号菜单
     *
     * @param params
     * @return
     */
    private List<MenusPo> userMenuIterator(Map<String, Object> params) {
        List<AdminMenu> adminMenus = adminMenuMapper.selectUserMenus(params);
        if (CollectionUtils.isEmpty(adminMenus)) {
            return null;
        }
        List<MenusPo> menusPoList = new ArrayList<>();
        for (AdminMenu child : adminMenus) {
            MenusPo childPo = new MenusPo();
            params.put("parentId", child.getMenuId());
            params.remove("menuLevel");
            BeanUtils.copyProperties(child, childPo);
            childPo.setChilds(userMenuIterator(params));
            menusPoList.add(childPo);
        }
        return menusPoList;
    }


    @Override
    BaseMapper<AdminUser> baseMapper() {
        return adminUserMapper;
    }
}
