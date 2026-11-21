package io.github.davigdomingues.paymenthub.domain.model;

import io.github.davigdomingues.paymenthub.domain.enums.CustomerTier;
import io.github.davigdomingues.paymenthub.domain.enums.PaymentMethod;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

/**
 * Contexto imutável de entrada compartilhado entre os padrões Strategy, Chain of Responsibility,
 * Decorator e Adapter durante o processamento de um pagamento.
 *
 * Ponto de maior impacto: garante integridade financeira e higiene de domínio logo na fronteira de
 * criação (fail-fast), impedindo propagação de valores nulos, negativos ou escalas decimais
 * inconsistentes através do pipeline.
 */
public record PaymentContext(
        String transactionCode,
        String idempotencyKey,
        String payerDocument,
        String payeeDocument,
        BigDecimal amount,
        PaymentMethod paymentMethod,
        CustomerTier customerTier,
        int installments,
        String pixKey,
        String description
) {
    /**
     * Construtor compacto responsável por validar campos obrigatórios e normalizar valores
     * financeiros (escala fixa de 2 casas com arredondamento bancário {@code HALF_EVEN}) e defaults
     * seguros de domínio.
     *
     * @throws NullPointerException quando campos obrigatórios ({@code transactionCode},
     *     {@code payerDocument}, {@code payeeDocument}, {@code amount}, {@code paymentMethod})
     *     forem nulos
     * @throws IllegalArgumentException quando strings obrigatórias estiverem em branco ou
     *     {@code amount} for menor ou igual a zero
     */
    public PaymentContext {
        transactionCode = requireNonBlank(transactionCode, "transactionCode");
        payerDocument = requireNonBlank(payerDocument, "payerDocument");
        payeeDocument = requireNonBlank(payeeDocument, "payeeDocument");
        Objects.requireNonNull(amount, "amount não pode ser nulo");
        if (amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("amount deve ser maior que zero");
        }
        // Normalização monetária bancária única para evitar drift entre Strategy, Adapter e persistência
        amount = amount.setScale(2, RoundingMode.HALF_EVEN);
        Objects.requireNonNull(paymentMethod, "paymentMethod não pode ser nulo");
        customerTier = customerTier != null ? customerTier : CustomerTier.STANDARD;
        installments = installments <= 0 ? 1 : installments;
        idempotencyKey = normalizeBlankToNull(idempotencyKey);
        pixKey = normalizeBlankToNull(pixKey);
        description = normalizeBlankToNull(description);
    }

    private static String requireNonBlank(String value, String fieldName) {
        Objects.requireNonNull(value, fieldName + " não pode ser nulo");
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException(fieldName + " não pode estar em branco");
        }
        return trimmed;
    }

    private static String normalizeBlankToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
