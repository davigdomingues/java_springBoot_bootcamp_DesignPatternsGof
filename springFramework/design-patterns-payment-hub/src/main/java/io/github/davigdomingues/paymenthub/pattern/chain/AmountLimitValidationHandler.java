package io.github.davigdomingues.paymenthub.pattern.chain;

import io.github.davigdomingues.paymenthub.domain.enums.PaymentMethod;
import io.github.davigdomingues.paymenthub.domain.model.PaymentContext;
import io.github.davigdomingues.paymenthub.domain.model.RiskAssessment;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * Padrão GoF: Chain of Responsibility (Elo 2 - Limites de Valor)
 *
 * Estrutura e impacto: avalia limites monetários sem acesso a banco antes do elo de velocidade:
 * bloqueia valores acima do teto global (padrão R$ 100.000,00) ou acima do teto específico de
 * Boleto (padrão R$ 50.000,00), e adiciona {@code +35} pontos de risco
 * ({@code HIGH_VALUE_TRANSACTION}) para operações acima de R$ 20.000,00, permitindo que a pontuação
 * seja somada a outros sinais no elo seguinte.
 */
public class AmountLimitValidationHandler extends ComplianceValidationHandler {

    private static final BigDecimal DEFAULT_MAX_GLOBAL_AMOUNT = new BigDecimal("100000.00");
    private static final BigDecimal DEFAULT_MAX_BOLETO_AMOUNT = new BigDecimal("50000.00");
    private static final BigDecimal DEFAULT_HIGH_VALUE_THRESHOLD = new BigDecimal("20000.00");
    private static final int HIGH_VALUE_RISK_POINTS = 35;

    private final BigDecimal maxGlobalAmount;
    private final BigDecimal maxBoletoAmount;
    private final BigDecimal highValueThreshold;

    public AmountLimitValidationHandler() {
        this(DEFAULT_MAX_GLOBAL_AMOUNT, DEFAULT_MAX_BOLETO_AMOUNT, DEFAULT_HIGH_VALUE_THRESHOLD);
    }

    /**
     * Inicializa limites configuráveis da etapa de valor.
     *
     * @throws NullPointerException quando qualquer limite informado for nulo
     */
    public AmountLimitValidationHandler(
            BigDecimal maxGlobalAmount,
            BigDecimal maxBoletoAmount,
            BigDecimal highValueThreshold
    ) {
        this.maxGlobalAmount = Objects.requireNonNull(maxGlobalAmount, "maxGlobalAmount não pode ser nulo");
        this.maxBoletoAmount = Objects.requireNonNull(maxBoletoAmount, "maxBoletoAmount não pode ser nulo");
        this.highValueThreshold = Objects.requireNonNull(highValueThreshold, "highValueThreshold não pode ser nulo");
    }

    @Override
    protected RiskAssessment evaluate(PaymentContext context, RiskAssessment current) {
        BigDecimal amount = context.amount();
        if (amount.compareTo(maxGlobalAmount) > 0) {
            return current.block("GLOBAL_AMOUNT_LIMIT_EXCEEDED");
        }
        if (context.paymentMethod() == PaymentMethod.BOLETO && amount.compareTo(maxBoletoAmount) > 0) {
            return current.block("BOLETO_AMOUNT_LIMIT_EXCEEDED");
        }
        // Acima do limiar de alto valor, eleva score sem bloquear para compor análise cumulativa
        if (amount.compareTo(highValueThreshold) > 0) {
            return current.withAddedRisk(HIGH_VALUE_RISK_POINTS, "HIGH_VALUE_TRANSACTION");
        }
        return current;
    }
}
