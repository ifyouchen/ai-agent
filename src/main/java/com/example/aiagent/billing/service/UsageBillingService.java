package com.example.aiagent.billing.service;

import com.example.aiagent.billing.config.BillingProperties;
import com.example.aiagent.billing.entity.UsageReservation;
import com.example.aiagent.billing.exception.BillingException;
import com.example.aiagent.billing.mapper.UsageReservationMapper;
import com.example.aiagent.billing.model.UsageChargeResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;

@Slf4j
@Service
@RequiredArgsConstructor
public class UsageBillingService {

    private final BillingProperties properties;
    private final BillingPricingService pricingService;
    private final BillingWalletService walletService;
    private final BillingNumberGenerator numberGenerator;
    private final UsageReservationMapper reservationMapper;

    @Transactional
    public UsageReservation beginUsage(String userId, String sessionId, String traceId,
                                       String modelName, int estimatedInputTokens) {
        if (!properties.isEnforcementEnabled()) {
            return UsageReservation.disabled();
        }
        walletService.getOrCreateWallet(userId);
        BigDecimal reservedCny = pricingService.estimateCny(modelName, estimatedInputTokens);
        UsageReservation reservation = UsageReservation.builder()
                .reservationNo(numberGenerator.reservationNo())
                .traceId(traceId)
                .sessionId(sessionId)
                .userId(userId)
                .modelName(modelName)
                .inputTokensEst(Math.max(estimatedInputTokens, 0))
                .outputTokensEst(properties.getDefaultMaxOutputTokens())
                .reservedCny(reservedCny)
                .status("RESERVED")
                .expiresAt(Instant.now().plusSeconds(properties.getReservationTtlMinutes() * 60L))
                .build();
        reservationMapper.insert(reservation);
        walletService.reserve(userId, reservedCny, reservation.getReservationNo());
        log.info("LLM 用量冻结创建 userId={} sessionId={} reservationNo={} model={} reservedCny={}",
                userId, sessionId, reservation.getReservationNo(), modelName, reservedCny);
        return reservation;
    }

    @Transactional
    public UsageChargeResult settleUsage(UsageReservation reservation, int inputTokens, int outputTokens) {
        if (reservation == null || !reservation.enabled()) {
            return UsageChargeResult.ZERO;
        }
        UsageChargeResult charge = pricingService.actualCharge(
                reservation.getModelName(), inputTokens, outputTokens);
        int marked = reservationMapper.markSettled(reservation.getReservationNo(), charge.costCny());
        if (marked == 0) {
            log.warn("LLM 用量冻结已非待结算状态 reservationNo={}", reservation.getReservationNo());
            throw BillingException.conflict("用量结算状态异常");
        }
        walletService.settle(reservation.getUserId(), reservation.getReservedCny(),
                charge.costCny(), reservation.getReservationNo());
        log.info("LLM 用量结算完成 userId={} reservationNo={} costUsd={} costCny={} tokens={}/{}",
                reservation.getUserId(), reservation.getReservationNo(), charge.costUsd(), charge.costCny(),
                inputTokens, outputTokens);
        return charge;
    }

    @Transactional
    public void releaseUsage(UsageReservation reservation, String reason) {
        if (reservation == null || !reservation.enabled()) {
            return;
        }
        int marked = reservationMapper.markReleased(reservation.getReservationNo());
        if (marked == 0) {
            log.debug("LLM 用量冻结释放跳过，状态已变化 reservationNo={}", reservation.getReservationNo());
            return;
        }
        walletService.release(reservation.getUserId(), reservation.getReservedCny(),
                reservation.getReservationNo(), reason);
        log.info("LLM 用量冻结释放 userId={} reservationNo={} reason={}",
                reservation.getUserId(), reservation.getReservationNo(), reason);
    }
}
