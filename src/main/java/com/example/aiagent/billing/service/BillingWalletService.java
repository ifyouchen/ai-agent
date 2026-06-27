package com.example.aiagent.billing.service;

import com.example.aiagent.billing.entity.BillingLedger;
import com.example.aiagent.billing.entity.BillingWallet;
import com.example.aiagent.billing.exception.BillingException;
import com.example.aiagent.billing.mapper.BillingLedgerMapper;
import com.example.aiagent.billing.mapper.BillingWalletMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;

@Slf4j
@Service
@RequiredArgsConstructor
public class BillingWalletService {

    private static final int MONEY_SCALE = 6;

    private final BillingWalletMapper walletMapper;
    private final BillingLedgerMapper ledgerMapper;
    private final BillingNumberGenerator numberGenerator;

    @Transactional
    public BillingWallet getOrCreateWallet(String userId) {
        requireUserId(userId);
        walletMapper.insertIfAbsent(userId);
        return walletMapper.findByUserId(userId);
    }

    @Transactional
    public void reserve(String userId, BigDecimal amount, String reservationNo) {
        requireUserId(userId);
        BigDecimal normalized = normalize(amount);
        int updated = walletMapper.reserve(userId, normalized);
        if (updated == 0) {
            log.warn("钱包冻结失败 userId={} reservationNo={} amountCny={}", userId, reservationNo, normalized);
            throw BillingException.paymentRequired("余额不足，请先充值");
        }
        BillingWallet wallet = walletMapper.findByUserId(userId);
        writeLedger(userId, "RESERVE", normalized.negate(), wallet.getAvailableBalanceCny(),
                "USAGE_RESERVATION", reservationNo, "reserve:" + reservationNo, "LLM 调用费用冻结");
        log.info("钱包冻结成功 userId={} reservationNo={} amountCny={} availableAfter={}",
                userId, reservationNo, normalized, wallet.getAvailableBalanceCny());
    }

    @Transactional
    public void settle(String userId, BigDecimal reserved, BigDecimal actual, String reservationNo) {
        requireUserId(userId);
        BigDecimal reservedAmount = normalize(reserved);
        BigDecimal actualAmount = normalize(actual);
        BigDecimal refund = reservedAmount.subtract(actualAmount).max(BigDecimal.ZERO).setScale(MONEY_SCALE);
        BigDecimal overage = actualAmount.subtract(reservedAmount).max(BigDecimal.ZERO).setScale(MONEY_SCALE);
        if (overage.signum() > 0) {
            log.error("实际扣费超过冻结金额 userId={} reservationNo={} reservedCny={} actualCny={} overageCny={}",
                    userId, reservationNo, reservedAmount, actualAmount, overage);
        }
        int updated = walletMapper.settleReserved(userId, reservedAmount, actualAmount, refund, overage);
        if (updated == 0) {
            log.error("钱包结算失败 userId={} reservationNo={} reservedCny={} actualCny={}",
                    userId, reservationNo, reservedAmount, actualAmount);
            throw BillingException.conflict("钱包结算失败，请联系管理员");
        }
        BillingWallet wallet = walletMapper.findByUserId(userId);
        writeLedger(userId, "SETTLE", actualAmount.negate(), wallet.getAvailableBalanceCny(),
                "USAGE_RESERVATION", reservationNo, "settle:" + reservationNo, "LLM 调用实际扣费");
        log.info("钱包结算成功 userId={} reservationNo={} reservedCny={} actualCny={} refundCny={} availableAfter={}",
                userId, reservationNo, reservedAmount, actualAmount, refund, wallet.getAvailableBalanceCny());
    }

    @Transactional
    public void release(String userId, BigDecimal reserved, String reservationNo, String remark) {
        requireUserId(userId);
        BigDecimal reservedAmount = normalize(reserved);
        int updated = walletMapper.releaseReserved(userId, reservedAmount);
        if (updated == 0) {
            log.warn("钱包释放冻结失败 userId={} reservationNo={} amountCny={}", userId, reservationNo, reservedAmount);
            return;
        }
        BillingWallet wallet = walletMapper.findByUserId(userId);
        writeLedger(userId, "RELEASE", reservedAmount, wallet.getAvailableBalanceCny(),
                "USAGE_RESERVATION", reservationNo, "release:" + reservationNo, remark);
        log.info("钱包释放冻结成功 userId={} reservationNo={} amountCny={} availableAfter={}",
                userId, reservationNo, reservedAmount, wallet.getAvailableBalanceCny());
    }

    @Transactional
    public void creditRecharge(String userId, BigDecimal amount, String orderNo) {
        requireUserId(userId);
        getOrCreateWallet(userId);
        BigDecimal normalized = normalize(amount);
        int updated = walletMapper.creditRecharge(userId, normalized);
        if (updated == 0) {
            log.error("充值入账失败 userId={} orderNo={} amountCny={}", userId, orderNo, normalized);
            throw BillingException.conflict("充值入账失败，请联系管理员");
        }
        BillingWallet wallet = walletMapper.findByUserId(userId);
        writeLedger(userId, "RECHARGE", normalized, wallet.getAvailableBalanceCny(),
                "RECHARGE_ORDER", orderNo, "recharge:" + orderNo, "充值入账");
        log.info("充值入账成功 userId={} orderNo={} amountCny={} availableAfter={}",
                userId, orderNo, normalized, wallet.getAvailableBalanceCny());
    }

    public BigDecimal normalize(BigDecimal amount) {
        return amount == null ? BigDecimal.ZERO.setScale(MONEY_SCALE)
                : amount.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    }

    private void writeLedger(String userId, String type, BigDecimal amount, BigDecimal balanceAfter,
                             String refType, String refId, String idempotencyKey, String remark) {
        ledgerMapper.insert(BillingLedger.builder()
                .ledgerNo(numberGenerator.ledgerNo())
                .userId(userId)
                .type(type)
                .amountCny(normalize(amount))
                .balanceAfterCny(normalize(balanceAfter))
                .refType(refType)
                .refId(refId)
                .idempotencyKey(idempotencyKey)
                .remark(remark)
                .build());
    }

    private void requireUserId(String userId) {
        if (userId == null || userId.isBlank()) {
            throw BillingException.badRequest("计费账户不能为空");
        }
    }
}
