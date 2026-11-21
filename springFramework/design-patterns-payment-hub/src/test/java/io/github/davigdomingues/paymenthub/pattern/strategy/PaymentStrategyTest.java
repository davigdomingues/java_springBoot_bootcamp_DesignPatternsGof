package io.github.davigdomingues.paymenthub.pattern.strategy;

import io.github.davigdomingues.paymenthub.domain.enums.CustomerTier;
import io.github.davigdomingues.paymenthub.domain.enums.PaymentMethod;
import io.github.davigdomingues.paymenthub.domain.enums.PaymentStatus;
import io.github.davigdomingues.paymenthub.domain.model.PaymentContext;
import io.github.davigdomingues.paymenthub.exception.BusinessRuleException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Testes unitários das implementações de {@link PaymentStrategy} (PIX, Cartão
 * de Crédito e Boleto).
 *
 * Cobre regras específicas de validação ({@link BusinessRuleException}),
 * cálculos de tarifas com
 * tetos/pisos por {@link CustomerTier} e definição do {@link PaymentStatus}
 * inicial.
 */
class PaymentStrategyTest {

    private final PixPaymentStrategy pixStrategy = new PixPaymentStrategy();
    private final CreditCardPaymentStrategy cardStrategy = new CreditCardPaymentStrategy();
    private final BoletoPaymentStrategy boletoStrategy = new BoletoPaymentStrategy();

    @Test
    void pixShouldBeFreeUpToThresholdOrVipAndCappedAboveThreshold() {
        PaymentContext standardLow = context(PaymentMethod.PIX, CustomerTier.STANDARD, "5000.00", 1, "alice@pix");
        PaymentContext standardHigh = context(PaymentMethod.PIX, CustomerTier.STANDARD, "8000.00", 1, "alice@pix");
        PaymentContext standardCapped = context(PaymentMethod.PIX, CustomerTier.STANDARD, "20000.00", 1, "alice@pix");
        PaymentContext vipHigh = context(PaymentMethod.PIX, CustomerTier.VIP, "20000.00", 1, "alice@pix");

        pixStrategy.validateSpecificRules(standardLow);
        assertThat(pixStrategy.calculateFee(standardLow)).isEqualByComparingTo("0.00");
        assertThat(pixStrategy.calculateFee(standardHigh)).isEqualByComparingTo("20.00");
        assertThat(pixStrategy.calculateFee(standardCapped)).isEqualByComparingTo("25.00");
        assertThat(pixStrategy.calculateFee(vipHigh)).isEqualByComparingTo("0.00");
        assertThat(pixStrategy.resolveInitialStatus()).isEqualTo(PaymentStatus.APPROVED);
    }

    @Test
    void pixShouldRejectMissingKeyOrInstallmentsGreaterThanOne() {
        PaymentContext missingKey = context(PaymentMethod.PIX, CustomerTier.STANDARD, "100.00", 1, " ");
        PaymentContext withInstallments = context(PaymentMethod.PIX, CustomerTier.STANDARD, "100.00", 2, "key@pix");

        assertThatThrownBy(() -> pixStrategy.validateSpecificRules(missingKey))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("pixKey");

        assertThatThrownBy(() -> pixStrategy.validateSpecificRules(withInstallments))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("parcelamento");
    }

    @Test
    void creditCardShouldCalculateProgressiveInstallmentFeesWithTierDiscountsAndFloor() {
        PaymentContext standard6x = context(PaymentMethod.CREDIT_CARD, CustomerTier.STANDARD, "1000.00", 6, null);
        // 2.49% + 5 * 0.50% = 4.99% => 49.90
        assertThat(cardStrategy.calculateFee(standard6x)).isEqualByComparingTo("49.90");

        PaymentContext premium1x = context(PaymentMethod.CREDIT_CARD, CustomerTier.PREMIUM, "1000.00", 1, null);
        // 2.49% - 0.50% = 1.99% => 19.90
        assertThat(cardStrategy.calculateFee(premium1x)).isEqualByComparingTo("19.90");

        PaymentContext vip1x = context(PaymentMethod.CREDIT_CARD, CustomerTier.VIP, "1000.00", 1, null);
        // 2.49% - 1.00% = 1.49% => 14.90
        assertThat(cardStrategy.calculateFee(vip1x)).isEqualByComparingTo("14.90");

        assertThat(cardStrategy.resolveInitialStatus()).isEqualTo(PaymentStatus.APPROVED);
    }

    @Test
    void creditCardShouldRejectInstallmentsOutsideValidRange() {
        PaymentContext invalid13x = context(PaymentMethod.CREDIT_CARD, CustomerTier.STANDARD, "1000.00", 13, null);
        assertThatThrownBy(() -> cardStrategy.validateSpecificRules(invalid13x))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("entre 1 e 12");
    }

    @Test
    void boletoShouldApplyFixedFeeByTierAndRequireMinimumAmountAndSingleInstallment() {
        PaymentContext standard = context(PaymentMethod.BOLETO, CustomerTier.STANDARD, "250.00", 1, null);
        PaymentContext premium = context(PaymentMethod.BOLETO, CustomerTier.PREMIUM, "250.00", 1, null);
        PaymentContext vip = context(PaymentMethod.BOLETO, CustomerTier.VIP, "250.00", 1, null);

        boletoStrategy.validateSpecificRules(standard);
        assertThat(boletoStrategy.calculateFee(standard)).isEqualByComparingTo("3.50");
        assertThat(boletoStrategy.calculateFee(premium)).isEqualByComparingTo("1.75");
        assertThat(boletoStrategy.calculateFee(vip)).isEqualByComparingTo("0.00");
        assertThat(boletoStrategy.resolveInitialStatus()).isEqualTo(PaymentStatus.PENDING_SETTLEMENT);

        PaymentContext belowMinimum = context(PaymentMethod.BOLETO, CustomerTier.STANDARD, "9.99", 1, null);
        assertThatThrownBy(() -> boletoStrategy.validateSpecificRules(belowMinimum))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("10.00");

        PaymentContext parceled = context(PaymentMethod.BOLETO, CustomerTier.STANDARD, "100.00", 2, null);
        assertThatThrownBy(() -> boletoStrategy.validateSpecificRules(parceled))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("parcelamento");
    }

    private PaymentContext context(
            PaymentMethod method,
            CustomerTier tier,
            String amount,
            int installments,
            String pixKey) {
        return new PaymentContext(
                "TXN-20261119-000001",
                null,
                "12345678901",
                "98765432100",
                new BigDecimal(amount),
                method,
                tier,
                installments,
                pixKey,
                "Strategy unit test");
    }
}
