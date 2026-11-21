package io.github.davigdomingues.paymenthub.pattern.decorator;

import io.github.davigdomingues.paymenthub.domain.model.PaymentContext;
import io.github.davigdomingues.paymenthub.domain.model.PaymentExecutionResult;
import io.github.davigdomingues.paymenthub.domain.model.RiskAssessment;
import io.github.davigdomingues.paymenthub.exception.BusinessRuleException;
import io.github.davigdomingues.paymenthub.exception.UnsupportedPaymentMethodException;
import io.github.davigdomingues.paymenthub.pattern.adapter.ExternalSettlementPort;
import io.github.davigdomingues.paymenthub.pattern.adapter.SettlementRequest;
import io.github.davigdomingues.paymenthub.pattern.adapter.SettlementResponse;
import io.github.davigdomingues.paymenthub.pattern.builder.PaymentReceiptBuilder;
import io.github.davigdomingues.paymenthub.pattern.factory.PaymentStrategyFactory;
import io.github.davigdomingues.paymenthub.pattern.strategy.PaymentStrategy;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

/**
 * Padrão GoF: Decorator (Concrete Component)
 *
 * Estrutura e ponto de maior impacto: representa o núcleo indivisível de execução após aprovação na
 * Chain of Responsibility. Integra quatro padrões em sequência: resolve a {@code PaymentStrategy}
 * via Factory, calcula tarifa e valor líquido ({@code HALF_EVEN}), efetiva a liquidação no
 * mainframe via {@link ExternalSettlementPort} (Adapter) e constrói o recibo base imutável via
 * {@link PaymentReceiptBuilder}. Os decorators externos enriquecem esse recibo sem modificar esta
 * classe.
 */
@Component
public class CorePaymentExecutionPipeline implements PaymentExecutionPipeline {

    private final PaymentStrategyFactory strategyFactory;
    private final ExternalSettlementPort settlementPort;

    public CorePaymentExecutionPipeline(
            PaymentStrategyFactory strategyFactory,
            ExternalSettlementPort settlementPort) {
        this.strategyFactory = Objects.requireNonNull(strategyFactory, "strategyFactory não pode ser nulo");
        this.settlementPort = Objects.requireNonNull(settlementPort, "settlementPort não pode ser nulo");
    }

    /**
     * Executa tarifa, liquidação externa e montagem do recibo base com cashback inicial zerado.
     *
     * @throws NullPointerException quando {@code context} ou {@code riskAssessment} forem nulos
     * @throws UnsupportedPaymentMethodException se o método não possuir estratégia registrada
     * @throws BusinessRuleException se a liquidação no adaptador legado falhar
     */
    @Override
    public PaymentExecutionResult execute(PaymentContext context, RiskAssessment riskAssessment) {
        Objects.requireNonNull(context, "context não pode ser nulo");
        Objects.requireNonNull(riskAssessment, "riskAssessment não pode ser nulo");

        PaymentStrategy strategy = strategyFactory.getStrategy(context.paymentMethod());
        BigDecimal feeAmount = strategy.calculateFee(context).setScale(2, RoundingMode.HALF_EVEN);
        BigDecimal netAmount = context.amount().subtract(feeAmount).setScale(2, RoundingMode.HALF_EVEN);

        SettlementResponse settlement = settlementPort.settle(new SettlementRequest(
                context.transactionCode(),
                context.amount(),
                context.paymentMethod(),
                context.payerDocument(),
                context.payeeDocument()));

        return PaymentReceiptBuilder.builder()
                .transactionCode(context.transactionCode())
                .paymentMethod(context.paymentMethod())
                .grossAmount(context.amount())
                .feeAmount(feeAmount)
                .cashbackAmount(BigDecimal.ZERO.setScale(2, RoundingMode.HALF_EVEN))
                .netAmount(netAmount)
                .status(strategy.resolveInitialStatus())
                .settlementWindow(strategy.settlementWindowDescription())
                .externalAuthorizationCode(settlement.authorizationCode())
                .clearingChannel(settlement.clearingChannel())
                .riskAssessment(riskAssessment)
                .build();
    }
}
