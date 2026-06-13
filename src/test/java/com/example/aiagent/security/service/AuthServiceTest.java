package com.example.aiagent.security.service;

import com.example.aiagent.security.dto.RegisterRequest;
import com.example.aiagent.security.entity.Organization;
import com.example.aiagent.security.entity.SysUser;
import com.example.aiagent.security.mapper.SysUserMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @Mock
    SysUserMapper sysUserMapper;

    @Mock
    UserDetailsServiceImpl userDetailsService;

    @Mock
    JwtService jwtService;

    @Mock
    AuthenticationManager authenticationManager;

    @Mock
    OrganizationService organizationService;

    @Mock
    EmailVerificationService emailVerificationService;

    PasswordEncoder passwordEncoder = new BCryptPasswordEncoder();
    AuthService authService;

    @BeforeEach
    void setUp() {
        authService = new AuthService(
                sysUserMapper,
                userDetailsService,
                jwtService,
                passwordEncoder,
                authenticationManager,
                organizationService,
                emailVerificationService);
    }

    @Test
    @DisplayName("注册成功时校验邮箱验证码并写入规范化邮箱")
    void registerVerifiesEmailCodeAndStoresEmail() {
        when(sysUserMapper.existsByUsername("alice")).thenReturn(false);
        when(sysUserMapper.existsByEmail("alice@example.com")).thenReturn(false);
        when(organizationService.createPersonalOrganization(org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(Organization.builder().orgId("org-user").build());
        when(jwtService.generateToken(org.mockito.ArgumentMatchers.anyString(), eq("alice"), anyList()))
                .thenReturn("jwt-token");
        when(jwtService.getExpirationSeconds()).thenReturn(86400L);

        var response = authService.register(new RegisterRequest(
                "alice", "secret123", "Alice@Example.com", "123456", null));

        assertThat(response.token()).isEqualTo("jwt-token");
        verify(emailVerificationService).verifyRegisterCode("alice@example.com", "123456");

        ArgumentCaptor<SysUser> userCaptor = ArgumentCaptor.forClass(SysUser.class);
        verify(sysUserMapper).insert(userCaptor.capture());
        assertThat(userCaptor.getValue().getEmail()).isEqualTo("alice@example.com");
        assertThat(userCaptor.getValue().getUsername()).isEqualTo("alice");
    }

    @Test
    @DisplayName("邮箱已注册时不消耗验证码")
    void registerRejectsDuplicateEmailBeforeVerifyCode() {
        when(sysUserMapper.existsByUsername("alice")).thenReturn(false);
        when(sysUserMapper.existsByEmail("alice@example.com")).thenReturn(true);

        assertThatThrownBy(() -> authService.register(new RegisterRequest(
                "alice", "secret123", "alice@example.com", "123456", null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("邮箱已被注册");

        verify(emailVerificationService, never()).verifyRegisterCode(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString());
        verify(sysUserMapper, never()).insert(org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("已登录用户修改密码时只校验邮箱验证码并更新新密码")
    void changePasswordUsesEmailCodeWithoutOldPassword() {
        SysUser user = SysUser.builder()
                .userId("user-1")
                .email("alice@example.com")
                .passwordHash(passwordEncoder.encode("old-secret"))
                .build();
        when(sysUserMapper.findByUserId("user-1")).thenReturn(java.util.Optional.of(user));

        authService.changePassword("user-1", "new-secret", "123456");

        verify(emailVerificationService).verifyChangePasswordCode("alice@example.com", "123456");
        ArgumentCaptor<String> hashCaptor = ArgumentCaptor.forClass(String.class);
        verify(sysUserMapper).updatePassword(eq("user-1"), hashCaptor.capture());
        assertThat(passwordEncoder.matches("new-secret", hashCaptor.getValue())).isTrue();
    }

    @Test
    @DisplayName("未绑定邮箱时不能通过验证码修改密码")
    void changePasswordRejectsUserWithoutEmail() {
        SysUser user = SysUser.builder()
                .userId("user-1")
                .passwordHash(passwordEncoder.encode("old-secret"))
                .build();
        when(sysUserMapper.findByUserId("user-1")).thenReturn(java.util.Optional.of(user));

        assertThatThrownBy(() -> authService.changePassword("user-1", "new-secret", "123456"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("未绑定邮箱");

        verify(emailVerificationService, never()).verifyChangePasswordCode(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString());
        verify(sysUserMapper, never()).updatePassword(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString());
    }
}
