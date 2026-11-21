package io.github.davigdomingues.paymenthub.pattern.decorator;

import io.github.davigdomingues.paymenthub.domain.enums.PaymentMethod;
import io.github.davigdomingues.paymenthub.domain.model.PaymentContext;
import io.github.davigdomingues.paymenthub.domain.model.PaymentExecutionResult;
import io.github.davigdomingues.paymenthub.domain.model.RiskAssessment;
import io.github.davigdomingues.paymenthub.pattern.builder.PaymentReceiptBuilder;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

/**
 * Padrão GoF: Decorator (Concrete Decorator - Benefício de Cashback)
 *
 * Estrutura e ponto de maior impacto: envolve o núcleo de execução para calcular e conceder
 * cashback sem acoplar regras promocionais à liquidação bancária. Reconstrói o recibo imutável via
 * {@link PaymentReceiptBuilder#from(PaymentExecutionResult)} registrando seu nome em
 * {@code appliedDecorators}. O método estático {@link #calculateCashback(PaymentContext)} é
 * reutilizado diretamente pelo {@code simulatePayment} no Facade, eliminando qualquer divergência
 * entre simulação e execução real.
 */
public class CashbackBenefitDecorator extends AbstractPaymentPipelineDecorator {

    private static final String DECORATOR_NAME = "CashbackBenefitDecorator";
    private static final BigDecimal VIP_RATE = new BigDecimal("0.02");
    private static final BigDecimal VIP_CAP = new BigDecimal("100.00");
    private static final BigDecimal PREMIUM_RATE = new BigDecimal("0.01");
    private static final BigDecimal PREMIUM_CAP = new BigDecimal("50.00");
    private static final BigDecimal PREMIUM_MIN_AMOUNT = new BigDecimal("200.00");

    public CashbackBenefitDecorator(PaymentExecutionPipeline delegate) {
        super(delegate);
    }

    /**
     * Executa o pipeline subjacente e gera novo resultado imutável enriquecido com o cashback.
     */
    @Override
    public PaymentExecutionResult execute(PaymentContext context, RiskAssessment riskAssessment) {
        PaymentExecutionResult baseResult = delegate.execute(context, riskAssessment);
        BigDecimal cashback = calculateCashback(context);

        // PaymentReceiptBuilder.from preserva integridade do recibo original sem mutação in-place
        return PaymentReceiptBuilder.from(baseResult)
                .cashbackAmount(cashback)
                .addDecoratorApplied(DECORATOR_NAME)
                .build();
    }

    /**
     * Calcula o valor de cashback elegível de acordo com o nível do cliente, meio de pagamento e
     * valor da operação (VIP: 2% teto R$ 100; PREMIUM: 1% teto R$ 50 para PIX ou valores &ge; R$
     * 200; STANDARD: R$ 0,00).
     *
     * @param context contexto imutável do pagamento
     * @return valor de cashback arredondado com 2 casas decimais ({@code HALF_EVEN})
     * @throws NullPointerException quando {@code context} for nulo
     */
    public static BigDecimal calculateCashback(PaymentContext context) {
        Objects.requireNonNull(context, "context não pode ser nulo");
        return switch (context.customerTier()) {
            case VIP -> context.amount()
                    .multiply(VIP_RATE)
                    .setScale(2, RoundingMode.HALF_EVEN)
                    .min(VIP_CAP);
            case PREMIUM -> isEligibleForPremiumCashback(context)
                    ? context.amount()
                            .multiply(PREMIUM_RATE)
                            .setScale(2, RoundingMode.HALF_EVEN)
                            .min(PREMIUM_CAP)
                    : BigDecimal.ZERO.setScale(2, RoundingMode.HALF_EVEN);
            case STANDARD -> BigDecimal.ZERO.setScale(2, RoundingMode.HALF_EVEN);
        };
    }

    private static boolean isEligibleForPremiumCashback(PaymentContext context) {
        return context.paymentMethod() == PaymentMethod.PIX
                || context.amount().compareTo(PREMIUM_MIN_AMOUNT) >= 0;
    }
}
