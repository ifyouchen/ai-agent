package com.example.aiagent.tool.repository;

import com.example.aiagent.tool.entity.UserAccount;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/**
 * 用户账户 Repository
 */
public interface UserAccountRepository extends JpaRepository<UserAccount, Long> {

    /**
     * 按用户 ID 查询账户信息。
     *
     * @param userId 用户 ID
     */
    Optional<UserAccount> findByUserId(String userId);
}
