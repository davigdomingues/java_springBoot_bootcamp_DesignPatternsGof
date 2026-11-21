package io.github.davigdomingues.paymenthub.pattern.decorator;

import io.github.davigdomingues.paymenthub.domain.model.PaymentContext;
import io.github.davigdomingues.paymenthub.domain.model.PaymentExecutionResult;
import io.github.davigdomingues.paymenthub.domain.model.RiskAssessment;
import io.github.davigdomingues.paymenthub.pattern.builder.PaymentReceiptBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.TimeUnit;

/**
 * Padrão GoF: Decorator (Concrete Decorator - Telemetria de Execução)
 *
 * Estrutura e impacto: atua como camada mais externa da pilha ({@code ExecutionTelemetryDecorator}
 * -> {@code CashbackBenefitDecorator} -> {@code Core}), capturando a latência total do
 * processamento (incluindo cálculo de cashback e liquidação no adaptador legado) com relógio
 * monotônico {@link System#nanoTime()} - imune a saltos de relógio de parede (NTP) - e gravando o
 * tempo no recibo imutável e na trilha {@code appliedDecorators}.
 */
public class ExecutionTelemetryDecorator extends AbstractPaymentPipelineDecorator {

    private static final Logger log = LoggerFactory.getLogger(ExecutionTelemetryDecorator.class);
    private static final String DECORATOR_NAME = "ExecutionTelemetryDecorator";

    public ExecutionTelemetryDecorator(PaymentExecutionPipeline delegate) {
        super(delegate);
    }

    /**
     * Mensura o tempo decorrido do pipeline delegado e devolve cópia imutável com
     * {@code processingTimeMs}.
     */
    @Override
    public PaymentExecutionResult execute(PaymentContext context, RiskAssessment riskAssessment) {
        // System.nanoTime fornece medição monotônica confiável de duração sem depender do relógio do SO
        long startedAtNanos = System.nanoTime();
        PaymentExecutionResult innerResult = delegate.execute(context, riskAssessment);
        long elapsedMs = Math.max(0L, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAtNanos));

        log.debug(
                "Pipeline de pagamento executado transactionCode={} method={} elapsedMs={}",
                context.transactionCode(),
                context.paymentMethod(),
                elapsedMs
        );

        return PaymentReceiptBuilder.from(innerResult)
                .processingTimeMs(elapsedMs)
                .addDecoratorApplied(DECORATOR_NAME)
                .build();
    }
}
