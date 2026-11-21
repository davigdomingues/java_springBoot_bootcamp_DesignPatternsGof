package io.github.davigdomingues.paymenthub.pattern.builder;

import io.github.davigdomingues.paymenthub.domain.enums.PaymentMethod;
import io.github.davigdomingues.paymenthub.domain.enums.PaymentStatus;
import io.github.davigdomingues.paymenthub.domain.model.PaymentExecutionResult;
import io.github.davigdomingues.paymenthub.domain.model.RiskAssessment;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Testes unitários do Builder {@link PaymentReceiptBuilder}.
 *
 * Verifica normalização de escala monetária, derivação automática de {@code netAmount},
 * imutabilidade de listas defensivas, preservação do objeto original em {@code from(...)} e falha
 * explícita ({@link NullPointerException}) para campos obrigatórios ausentes.
 */
class PaymentReceiptBuilderTest {

    @Test
    void shouldBuildImmutableResultWithCalculatedNetAmountAndNormalizedScales() {
        PaymentExecutionResult result = PaymentReceiptBuilder.builder()
                .transactionCode("TXN-20261119-000001")
                .paymentMethod(PaymentMethod.PIX)
                .grossAmount(new BigDecimal("1000"))
                .feeAmount(new BigDecimal("10.5"))
                .status(PaymentStatus.APPROVED)
                .settlementWindow("Instantâneo (24/7 via SPI/Pix)")
                .externalAuthorizationCode("AUTH-123456")
                .clearingChannel("MAINFRAME_SPI")
                .riskAssessment(RiskAssessment.clean())
                .addDecoratorApplied("CashbackBenefitDecorator")
                .build();

        assertThat(result.grossAmount()).isEqualByComparingTo("1000.00");
        assertThat(result.feeAmount()).isEqualByComparingTo("10.50");
        assertThat(result.cashbackAmount()).isEqualByComparingTo("0.00");
        assertThat(result.netAmount()).isEqualByComparingTo("989.50");
        assertThat(result.appliedDecorators()).containsExactly("CashbackBenefitDecorator");
        // Garante cópia defensiva imutável contra alteração pós-build
        assertThatThrownBy(() -> result.appliedDecorators().add("Another"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void shouldCopyAndEnrichExistingResultWithoutMutatingOriginal() {
        PaymentExecutionResult original = PaymentReceiptBuilder.builder()
                .transactionCode("TXN-20261119-000002")
                .paymentMethod(PaymentMethod.CREDIT_CARD)
                .grossAmount(new BigDecimal("500.00"))
                .feeAmount(new BigDecimal("12.45"))
                .status(PaymentStatus.APPROVED)
                .addDecoratorApplied("CashbackBenefitDecorator")
                .build();

        // Ponto crítico para Decorators: enriquecimento cria nova instância sem alterar o recibo base
        PaymentExecutionResult enriched = PaymentReceiptBuilder.from(original)
                .cashbackAmount(new BigDecimal("10.00"))
                .processingTimeMs(15L)
                .addDecoratorApplied("ExecutionTelemetryDecorator")
                .build();

        assertThat(original.cashbackAmount()).isEqualByComparingTo("0.00");
        assertThat(original.processingTimeMs()).isZero();
        assertThat(original.appliedDecorators()).containsExactly("CashbackBenefitDecorator");

        assertThat(enriched.cashbackAmount()).isEqualByComparingTo("10.00");
        assertThat(enriched.processingTimeMs()).isEqualTo(15L);
        assertThat(enriched.appliedDecorators())
                .containsExactly("CashbackBenefitDecorator", "ExecutionTelemetryDecorator");
    }

    @Test
    void shouldFailWhenMandatoryFieldsAreMissing() {
        assertThatThrownBy(() -> PaymentReceiptBuilder.builder()
                .paymentMethod(PaymentMethod.PIX)
                .grossAmount(new BigDecimal("100.00"))
                .feeAmount(BigDecimal.ZERO)
                .status(PaymentStatus.APPROVED)
                .build())
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("transactionCode");
    }
}
