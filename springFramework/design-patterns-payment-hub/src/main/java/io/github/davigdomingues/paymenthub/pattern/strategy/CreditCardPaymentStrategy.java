package io.github.davigdomingues.paymenthub.pattern.strategy;

import io.github.davigdomingues.paymenthub.domain.enums.PaymentMethod;
import io.github.davigdomingues.paymenthub.domain.enums.PaymentStatus;
import io.github.davigdomingues.paymenthub.domain.model.PaymentContext;
import io.github.davigdomingues.paymenthub.exception.BusinessRuleException;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Padrão GoF: Strategy (Implementação Concreta - Cartão de Crédito)
 *
 * Estrutura e impacto: governa operações com parcelamento de 1 a 12 vezes. Calcula a alíquota
 * efetiva pela fórmula {@code max(0,99%, 2,49% + (parcelas - 1) * 0,50% - descontoTier)} com
 * arredondamento bancário {@code HALF_EVEN}, garantindo piso mínimo de 0,99% mesmo com desconto VIP
 * (1,00%) ou PREMIUM (0,50%), e liquida com status {@code APPROVED}.
 */
@Component
public class CreditCardPaymentStrategy implements PaymentStrategy {

    private static final int MIN_INSTALLMENTS = 1;
    private static final int MAX_INSTALLMENTS = 12;
    private static final BigDecimal BASE_RATE = new BigDecimal("0.0249");
    private static final BigDecimal INSTALLMENT_INCREMENT_RATE = new BigDecimal("0.0050");
    private static final BigDecimal PREMIUM_DISCOUNT_RATE = new BigDecimal("0.0050");
    private static final BigDecimal VIP_DISCOUNT_RATE = new BigDecimal("0.0100");
    private static final BigDecimal FLOOR_RATE = new BigDecimal("0.0099");

    @Override
    public PaymentMethod supportedMethod() {
        return PaymentMethod.CREDIT_CARD;
    }

    /**
     * Garante que o parcelamento esteja na faixa operacional permitida (1 a 12 parcelas).
     *
     * @throws BusinessRuleException se {@code installments < 1} ou {@code installments > 12}
     */
    @Override
    public void validateSpecificRules(PaymentContext context) {
        if (context.installments() < MIN_INSTALLMENTS || context.installments() > MAX_INSTALLMENTS) {
            throw new BusinessRuleException("O parcelamento no cartão de crédito deve estar entre 1 e 12");
        }
    }

    /**
     * Calcula a tarifa percentual progressiva por parcela descontando o benefício do tier até o
     * piso de 0,99%.
     */
    @Override
    public BigDecimal calculateFee(PaymentContext context) {
        BigDecimal installmentIncrement = INSTALLMENT_INCREMENT_RATE
                .multiply(BigDecimal.valueOf(context.installments() - 1L));
        BigDecimal tierDiscount = switch (context.customerTier()) {
            case STANDARD -> BigDecimal.ZERO;
            case PREMIUM -> PREMIUM_DISCOUNT_RATE;
            case VIP -> VIP_DISCOUNT_RATE;
        };

        // Piso tarifário protege margem mínima de adquirente independentemente de descontos
        BigDecimal effectiveRate = BASE_RATE
                .add(installmentIncrement)
                .subtract(tierDiscount)
                .max(FLOOR_RATE);

        return context.amount()
                .multiply(effectiveRate)
                .setScale(2, RoundingMode.HALF_EVEN);
    }

    @Override
    public PaymentStatus resolveInitialStatus() {
        return PaymentStatus.APPROVED;
    }

    @Override
    public String settlementWindowDescription() {
        return "D+1 dia útil (adquirente de cartão)";
    }
}
