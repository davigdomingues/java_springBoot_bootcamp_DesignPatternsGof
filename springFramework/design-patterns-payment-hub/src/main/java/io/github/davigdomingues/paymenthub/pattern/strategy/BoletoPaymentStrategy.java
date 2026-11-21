package io.github.davigdomingues.paymenthub.pattern.strategy;

import io.github.davigdomingues.paymenthub.domain.enums.PaymentMethod;
import io.github.davigdomingues.paymenthub.domain.enums.PaymentStatus;
import io.github.davigdomingues.paymenthub.domain.model.PaymentContext;
import io.github.davigdomingues.paymenthub.exception.BusinessRuleException;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Padrão GoF: Strategy (Implementação Concreta - Boleto Bancário)
 *
 * Estrutura e impacto: aplica tarifa fixa por tier do cliente (R$ 3,50 STANDARD, R$ 1,75 PREMIUM,
 * R$ 0,00 VIP), exige emissão à vista e valor mínimo de R$ 10,00. Seu principal impacto no ciclo de
 * vida é {@link #resolveInitialStatus()} retornar {@link PaymentStatus#PENDING_SETTLEMENT},
 * refletindo compensação bancária em até 2 dias úteis na CIP.
 */
@Component
public class BoletoPaymentStrategy implements PaymentStrategy {

    private static final BigDecimal MINIMUM_BOLETO_AMOUNT = new BigDecimal("10.00");
    private static final BigDecimal STANDARD_FEE = new BigDecimal("3.50");
    private static final BigDecimal PREMIUM_FEE = new BigDecimal("1.75");
    private static final BigDecimal VIP_FEE = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_EVEN);

    @Override
    public PaymentMethod supportedMethod() {
        return PaymentMethod.BOLETO;
    }

    /**
     * Valida emissão em parcela única e piso mínimo operacional de R$ 10,00 para boleto.
     *
     * @throws BusinessRuleException se {@code installments != 1} ou {@code amount < 10.00}
     */
    @Override
    public void validateSpecificRules(PaymentContext context) {
        if (context.installments() != 1) {
            throw new BusinessRuleException("Pagamentos via boleto não suportam parcelamento superior a 1");
        }
        if (context.amount().compareTo(MINIMUM_BOLETO_AMOUNT) < 0) {
            throw new BusinessRuleException("Pagamentos via boleto exigem valor mínimo de 10.00");
        }
    }

    /**
     * Retorna a tarifa fixa correspondente ao nível de relacionamento do cliente.
     */
    @Override
    public BigDecimal calculateFee(PaymentContext context) {
        return switch (context.customerTier()) {
            case STANDARD -> STANDARD_FEE;
            case PREMIUM -> PREMIUM_FEE;
            case VIP -> VIP_FEE;
        };
    }

    /**
     * Inicia boletos como {@link PaymentStatus#PENDING_SETTLEMENT} por dependerem de compensação
     * CIP.
     */
    @Override
    public PaymentStatus resolveInitialStatus() {
        return PaymentStatus.PENDING_SETTLEMENT;
    }

    @Override
    public String settlementWindowDescription() {
        return "Até 2 dias úteis (compensação CIP)";
    }
}
