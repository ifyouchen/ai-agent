package com.example.aiagent.security.service;

import com.example.aiagent.security.entity.EmailVerificationCode;
import com.example.aiagent.security.mapper.EmailVerificationCodeMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EmailVerificationServiceTest {

    @Mock
    EmailVerificationCodeMapper codeMapper;

    @Mock
    ObjectProvider<JavaMailSender> mailSenderProvider;

    @Mock
    JavaMailSender mailSender;

    PasswordEncoder passwordEncoder = new BCryptPasswordEncoder();
    EmailVerificationService service;

    @BeforeEach
    void setUp() {
        service = new EmailVerificationService(codeMapper, mailSenderProvider, passwordEncoder);
        ReflectionTestUtils.setField(service, "codeTtlMinutes", 10L);
        ReflectionTestUtils.setField(service, "resendCooldownSeconds", 60L);
        ReflectionTestUtils.setField(service, "maxAttempts", 5);
        ReflectionTestUtils.setField(service, "emailFrom", "noreply@example.com");
    }

    @Test
    @DisplayName("发送注册验证码成功后发送邮件并保存哈希记录")
    void sendRegisterCodeSendsEmailAndStoresHash() {
        when(codeMapper.findLatestByEmailAndPurpose("user@example.com", "REGISTER"))
                .thenReturn(Optional.empty());
        when(mailSenderProvider.getIfAvailable()).thenReturn(mailSender);

        service.sendRegisterCode("User@Example.com");

        ArgumentCaptor<SimpleMailMessage> mailCaptor = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mailSender).send(mailCaptor.capture());
        assertThat(mailCaptor.getValue().getTo()).containsExactly("user@example.com");
        assertThat(mailCaptor.getValue().getSubject()).isEqualTo("AI Agent 注册验证码");

        ArgumentCaptor<EmailVerificationCode> codeCaptor = ArgumentCaptor.forClass(EmailVerificationCode.class);
        verify(codeMapper).insert(codeCaptor.capture());
        assertThat(codeCaptor.getValue().getEmail()).isEqualTo("user@example.com");
        assertThat(codeCaptor.getValue().getPurpose()).isEqualTo("REGISTER");
        assertThat(codeCaptor.getValue().getCodeHash()).isNotBlank();
    }

    @Test
    @DisplayName("60 秒内重复发送验证码会被拒绝")
    void sendRegisterCodeRejectsCooldown() {
        when(codeMapper.findLatestByEmailAndPurpose("user@example.com", "REGISTER"))
                .thenReturn(Optional.of(EmailVerificationCode.builder()
                        .email("user@example.com")
                        .purpose("REGISTER")
                        .createdAt(Instant.now())
                        .build()));

        assertThatThrownBy(() -> service.sendRegisterCode("user@example.com"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("发送太频繁");

        verify(mailSenderProvider, never()).getIfAvailable();
        verify(codeMapper, never()).insert(any());
    }

    @Test
    @DisplayName("SMTP 未配置时不创建验证码记录")
    void sendRegisterCodeRejectsMissingSmtp() {
        when(codeMapper.findLatestByEmailAndPurpose("user@example.com", "REGISTER"))
                .thenReturn(Optional.empty());
        when(mailSenderProvider.getIfAvailable()).thenReturn(null);

        assertThatThrownBy(() -> service.sendRegisterCode("user@example.com"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("邮件服务未配置");

        verify(codeMapper, never()).insert(any());
    }

    @Test
    @DisplayName("验证码正确时标记为已使用")
    void verifyRegisterCodeMarksUsed() {
        when(codeMapper.findLatestByEmailAndPurpose("user@example.com", "REGISTER"))
                .thenReturn(Optional.of(EmailVerificationCode.builder()
                        .id(1L)
                        .email("user@example.com")
                        .purpose("REGISTER")
                        .codeHash(passwordEncoder.encode("123456"))
                        .expiresAt(Instant.now().plusSeconds(600))
                        .attempts(0)
                        .build()));

        service.verifyRegisterCode("user@example.com", "123456");

        verify(codeMapper).markUsed(any(), any());
        verify(codeMapper, never()).incrementAttempts(any());
    }

    @Test
    @DisplayName("验证码错误时增加尝试次数")
    void verifyRegisterCodeIncrementsAttemptsWhenWrong() {
        when(codeMapper.findLatestByEmailAndPurpose("user@example.com", "REGISTER"))
                .thenReturn(Optional.of(EmailVerificationCode.builder()
                        .id(1L)
                        .email("user@example.com")
                        .purpose("REGISTER")
                        .codeHash(passwordEncoder.encode("654321"))
                        .expiresAt(Instant.now().plusSeconds(600))
                        .attempts(0)
                        .build()));

        assertThatThrownBy(() -> service.verifyRegisterCode("user@example.com", "123456"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("验证码不正确");

        verify(codeMapper).incrementAttempts(1L);
        verify(codeMapper, never()).markUsed(any(), any());
    }
}
