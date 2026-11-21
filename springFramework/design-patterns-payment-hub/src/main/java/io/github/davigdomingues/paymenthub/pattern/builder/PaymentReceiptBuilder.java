package io.github.davigdomingues.paymenthub.pattern.builder;

import io.github.davigdomingues.paymenthub.domain.enums.PaymentMethod;
import io.github.davigdomingues.paymenthub.domain.enums.PaymentStatus;
import io.github.davigdomingues.paymenthub.domain.model.PaymentExecutionResult;
import io.github.davigdomingues.paymenthub.domain.model.RiskAssessment;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Padrão GoF: Builder
 *
 * Estrutura e ponto de maior impacto: possui papel duplo na arquitetura: (1) construir o
 * {@link PaymentExecutionResult} base no {@code CorePaymentExecutionPipeline} validando invariantes
 * financeiras obrigatórias, e (2) possibilitar cópia/enriquecimento imutável via
 * {@link #from(PaymentExecutionResult)} para que os Decorators acrescentem cashback, telemetria e
 * trilha de decorators aplicados sem mutar o resultado original.
 */
public final class PaymentReceiptBuilder {

    private String transactionCode;
    private PaymentMethod paymentMethod;
    private BigDecimal grossAmount;
    private BigDecimal feeAmount;
    private BigDecimal cashbackAmount = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_EVEN);
    private BigDecimal netAmount;
    private PaymentStatus status;
    private String settlementWindow;
    private String externalAuthorizationCode;
    private String clearingChannel;
    private RiskAssessment riskAssessment = RiskAssessment.clean();
    private long processingTimeMs;
    private final List<String> appliedDecorators = new ArrayList<>();

    private PaymentReceiptBuilder() {
    }

    /**
     * Inicia um novo builder vazio.
     *
     * @return instância do builder
     */
    public static PaymentReceiptBuilder builder() {
        return new PaymentReceiptBuilder();
    }

    /**
     * Cria um builder pré-populado a partir de um resultado imutável existente, permitindo que
     * decorators adicionem informações sem mutar o resultado original.
     *
     * @param existing resultado base já construído
     * @return novo builder com os atributos copiados
     * @throws NullPointerException quando {@code existing} for nulo
     */
    public static PaymentReceiptBuilder from(PaymentExecutionResult existing) {
        Objects.requireNonNull(existing, "O resultado existente não pode ser nulo");
        PaymentReceiptBuilder builder = new PaymentReceiptBuilder()
                .transactionCode(existing.transactionCode())
                .paymentMethod(existing.paymentMethod())
                .grossAmount(existing.grossAmount())
                .feeAmount(existing.feeAmount())
                .cashbackAmount(existing.cashbackAmount())
                .netAmount(existing.netAmount())
                .status(existing.status())
                .settlementWindow(existing.settlementWindow())
                .externalAuthorizationCode(existing.externalAuthorizationCode())
                .clearingChannel(existing.clearingChannel())
                .riskAssessment(existing.riskAssessment())
                .processingTimeMs(existing.processingTimeMs());
        builder.appliedDecorators.addAll(existing.appliedDecorators());
        return builder;
    }

    public PaymentReceiptBuilder transactionCode(String transactionCode) {
        this.transactionCode = transactionCode;
        return this;
    }

    public PaymentReceiptBuilder paymentMethod(PaymentMethod paymentMethod) {
        this.paymentMethod = paymentMethod;
        return this;
    }

    public PaymentReceiptBuilder grossAmount(BigDecimal grossAmount) {
        this.grossAmount = grossAmount;
        return this;
    }

    public PaymentReceiptBuilder feeAmount(BigDecimal feeAmount) {
        this.feeAmount = feeAmount;
        return this;
    }

    public PaymentReceiptBuilder cashbackAmount(BigDecimal cashbackAmount) {
        this.cashbackAmount = cashbackAmount;
        return this;
    }

    public PaymentReceiptBuilder netAmount(BigDecimal netAmount) {
        this.netAmount = netAmount;
        return this;
    }

    public PaymentReceiptBuilder status(PaymentStatus status) {
        this.status = status;
        return this;
    }

    public PaymentReceiptBuilder settlementWindow(String settlementWindow) {
        this.settlementWindow = settlementWindow;
        return this;
    }

    public PaymentReceiptBuilder externalAuthorizationCode(String externalAuthorizationCode) {
        this.externalAuthorizationCode = externalAuthorizationCode;
        return this;
    }

    public PaymentReceiptBuilder clearingChannel(String clearingChannel) {
        this.clearingChannel = clearingChannel;
        return this;
    }

    public PaymentReceiptBuilder riskAssessment(RiskAssessment riskAssessment) {
        this.riskAssessment = riskAssessment;
        return this;
    }

    public PaymentReceiptBuilder processingTimeMs(long processingTimeMs) {
        this.processingTimeMs = Math.max(0L, processingTimeMs);
        return this;
    }

    /**
     * Registra o nome de um decorator aplicado ao pipeline de execução.
     *
     * @param decoratorName nome descritivo do decorator
     * @return este builder
     */
    public PaymentReceiptBuilder addDecoratorApplied(String decoratorName) {
        if (decoratorName != null && !decoratorName.isBlank()) {
            this.appliedDecorators.add(decoratorName.trim());
        }
        return this;
    }

    /**
     * Valida campos obrigatórios, normaliza escalas decimais ({@code HALF_EVEN}), deriva
     * {@code netAmount = grossAmount - feeAmount} quando omitido e constrói o resultado imutável.
     *
     * @return {@link PaymentExecutionResult} validado e imutável
     * @throws NullPointerException quando {@code transactionCode}, {@code paymentMethod},
     *     {@code grossAmount}, {@code feeAmount} ou {@code status} forem nulos
     */
    public PaymentExecutionResult build() {
        Objects.requireNonNull(transactionCode, "transactionCode é obrigatório");
        Objects.requireNonNull(paymentMethod, "paymentMethod é obrigatório");
        Objects.requireNonNull(grossAmount, "grossAmount é obrigatório");
        Objects.requireNonNull(feeAmount, "feeAmount é obrigatório");
        Objects.requireNonNull(status, "status é obrigatório");

        BigDecimal normalizedGross = grossAmount.setScale(2, RoundingMode.HALF_EVEN);
        BigDecimal normalizedFee = feeAmount.setScale(2, RoundingMode.HALF_EVEN);
        BigDecimal normalizedCashback = (cashbackAmount != null ? cashbackAmount : BigDecimal.ZERO)
                .setScale(2, RoundingMode.HALF_EVEN);
        // Cálculo automático de netAmount assegura coerência quando não sobrescrito explicitamente
        BigDecimal resolvedNet = (netAmount != null ? netAmount : normalizedGross.subtract(normalizedFee))
                .setScale(2, RoundingMode.HALF_EVEN);
        RiskAssessment resolvedRisk = riskAssessment != null ? riskAssessment : RiskAssessment.clean();

        return new PaymentExecutionResult(
                transactionCode,
                paymentMethod,
                normalizedGross,
                normalizedFee,
                normalizedCashback,
                resolvedNet,
                status,
                settlementWindow,
                externalAuthorizationCode,
                clearingChannel,
                resolvedRisk,
                processingTimeMs,
                List.copyOf(appliedDecorators)
        );
    }
}
