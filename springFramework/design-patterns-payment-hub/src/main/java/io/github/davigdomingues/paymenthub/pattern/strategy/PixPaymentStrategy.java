package io.github.davigdomingues.paymenthub.pattern.strategy;

import io.github.davigdomingues.paymenthub.domain.enums.CustomerTier;
import io.github.davigdomingues.paymenthub.domain.enums.PaymentMethod;
import io.github.davigdomingues.paymenthub.domain.enums.PaymentStatus;
import io.github.davigdomingues.paymenthub.domain.model.PaymentContext;
import io.github.davigdomingues.paymenthub.exception.BusinessRuleException;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Padrão GoF: Strategy (Implementação Concreta - PIX)
 *
 * Estrutura e impacto: atende transferências instantâneas via SPI/Pix. Exige {@code pixKey} não
 * vazia e pagamento à vista ({@code installments == 1}), aplica isenção tarifária para {@code VIP}
 * ou valores até R$ 5.000,00 e cobra {@code 0,25%} com teto fixo de R$ 25,00 acima desse limite,
 * liquidando imediatamente em {@code APPROVED}.
 */
@Component
public class PixPaymentStrategy implements PaymentStrategy {

    private static final BigDecimal EXEMPTION_THRESHOLD = new BigDecimal("5000.00");
    private static final BigDecimal FEE_RATE = new BigDecimal("0.0025");
    private static final BigDecimal FEE_CAP = new BigDecimal("25.00");

    @Override
    public PaymentMethod supportedMethod() {
        return PaymentMethod.PIX;
    }

    /**
     * Valida presença de chave PIX e unicidade de parcela antes da etapa de compliance.
     *
     * @throws BusinessRuleException se {@code pixKey} estiver ausente/vazia ou
     *     {@code installments != 1}
     */
    @Override
    public void validateSpecificRules(PaymentContext context) {
        if (context.pixKey() == null || context.pixKey().isBlank()) {
            throw new BusinessRuleException("Pagamentos via PIX exigem uma pixKey não vazia");
        }
        if (context.installments() != 1) {
            throw new BusinessRuleException("Pagamentos via PIX não suportam parcelamento superior a 1");
        }
    }

    /**
     * Aplica isenção para VIP/valores até R$ 5.000,00 ou 0,25% limitado a R$ 25,00 via
     * {@code HALF_EVEN}.
     */
    @Override
    public BigDecimal calculateFee(PaymentContext context) {
        if (context.customerTier() == CustomerTier.VIP
                || context.amount().compareTo(EXEMPTION_THRESHOLD) <= 0) {
            return BigDecimal.ZERO.setScale(2, RoundingMode.HALF_EVEN);
        }
        BigDecimal computed = context.amount()
                .multiply(FEE_RATE)
                .setScale(2, RoundingMode.HALF_EVEN);
        // Teto tarifário impede cobrança excessiva em transferências PIX de alto valor
        return computed.min(FEE_CAP);
    }

    @Override
    public PaymentStatus resolveInitialStatus() {
        return PaymentStatus.APPROVED;
    }

    @Override
    public String settlementWindowDescription() {
        return "Instantâneo (24/7 via SPI/Pix)";
    }
}
