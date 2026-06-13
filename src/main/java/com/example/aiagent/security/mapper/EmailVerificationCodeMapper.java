package com.example.aiagent.security.mapper;

import com.example.aiagent.security.entity.EmailVerificationCode;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.Instant;
import java.util.Optional;

@Mapper
public interface EmailVerificationCodeMapper {

    Optional<EmailVerificationCode> findLatestByEmailAndPurpose(@Param("email") String email,
                                                                @Param("purpose") String purpose);

    void insert(EmailVerificationCode code);

    void incrementAttempts(@Param("id") Long id);

    void markUsed(@Param("id") Long id, @Param("usedAt") Instant usedAt);
}
