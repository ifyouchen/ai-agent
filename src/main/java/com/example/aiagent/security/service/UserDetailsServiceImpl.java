package com.example.aiagent.security.service;

import com.example.aiagent.security.entity.SysUser;
import com.example.aiagent.security.mapper.SysUserMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Spring Security UserDetailsService 实现
 *
 * 从 PostgreSQL 加载用户认证信息，供 Spring Security 的
 * DaoAuthenticationProvider 使用（登录时调用）。
 *
 * 注意：这里的"用户名"对应 biz_user_account.username，
 * JWT 的 subject 存的是 userId（唯一性更强，不受改名影响）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserDetailsServiceImpl implements UserDetailsService {

    private final SysUserMapper sysUserMapper;

    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        SysUser user = sysUserMapper.findByUsername(username)
                .orElseThrow(() -> {
                    log.warn("登录失败：用户名不存在 username={}", username);
                    return new UsernameNotFoundException("用户不存在：" + username);
                });

        if (user.getEnabled() == null || user.getEnabled() == 0) {
            log.warn("登录失败：账号已禁用 username={}", username);
            throw new UsernameNotFoundException("账号已禁用");
        }

        List<SimpleGrantedAuthority> authorities = user.getRoleList().stream()
                .map(SimpleGrantedAuthority::new)
                .toList();

        return User.builder()
                .username(user.getUsername())
                .password(user.getPasswordHash())
                .authorities(authorities)
                .build();
    }

    /**
     * 加载完整的 SysUser（AuthController 注册/登录时使用，需要 userId 等额外字段）
     */
    public SysUser loadSysUserByUsername(String username) {
        return sysUserMapper.findByUsername(username)
                .orElseThrow(() -> new UsernameNotFoundException("用户不存在：" + username));
    }
}

