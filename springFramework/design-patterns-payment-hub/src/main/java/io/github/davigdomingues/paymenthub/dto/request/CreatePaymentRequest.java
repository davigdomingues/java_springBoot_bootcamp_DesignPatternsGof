package io.github.davigdomingues.paymenthub.dto.request;

import io.github.davigdomingues.paymenthub.domain.enums.CustomerTier;
import io.github.davigdomingues.paymenthub.domain.enums.PaymentMethod;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/**
 * Payload imutável de entrada para processamento e simulação de pagamentos.
 *
 * Fronteira de validação e impacto: aplica Bean Validation na borda HTTP ({@code @Valid}),
 * convertendo erros sintáticos (formato de CPF/CNPJ, valor mínimo, faixa de parcelas) em HTTP
 * {@code 400 Bad Request} via {@code GlobalExceptionHandler} antes de alcançar as regras semânticas
 * de {@code PaymentStrategy} e compliance (HTTP {@code 422}).
 */
public record CreatePaymentRequest(
        @NotBlank(message = "payerDocument não pode estar em branco")
        @Pattern(regexp = "\\d{11}|\\d{14}", message = "payerDocument deve ser um CPF de 11 dígitos ou CNPJ de 14 dígitos")
        String payerDocument,

        @NotBlank(message = "payeeDocument não pode estar em branco")
        @Pattern(regexp = "\\d{11}|\\d{14}", message = "payeeDocument deve ser um CPF de 11 dígitos ou CNPJ de 14 dígitos")
        String payeeDocument,

        @NotNull(message = "amount não pode ser nulo")
        @DecimalMin(value = "0.01", message = "amount deve ser no mínimo 0.01")
        BigDecimal amount,

        @NotNull(message = "paymentMethod não pode ser nulo")
        PaymentMethod paymentMethod,

        CustomerTier customerTier,

        @Min(value = 1, message = "installments deve ser no mínimo 1")
        @Max(value = 12, message = "installments não pode exceder 12")
        Integer installments,

        String pixKey,

        @Size(max = 100, message = "idempotencyKey não pode exceder 100 caracteres")
        String idempotencyKey,

        @Size(max = 255, message = "description não pode exceder 255 caracteres")
        String description
) {
    /**
     * Retorna o nível de relacionamento informado ou {@link CustomerTier#STANDARD} por padrão.
     *
     * @return nível de relacionamento efetivo
     */
    public CustomerTier resolvedCustomerTier() {
        return customerTier != null ? customerTier : CustomerTier.STANDARD;
    }

    /**
     * Retorna o número de parcelas informado ou {@code 1} por padrão.
     *
     * @return quantidade efetiva de parcelas
     */
    public int resolvedInstallments() {
        return installments != null ? installments : 1;
    }
}
