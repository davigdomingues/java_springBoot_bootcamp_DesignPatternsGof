package io.github.davigdomingues.paymenthub.domain.entity;

import io.github.davigdomingues.paymenthub.domain.enums.CustomerTier;
import io.github.davigdomingues.paymenthub.domain.enums.PaymentMethod;
import io.github.davigdomingues.paymenthub.domain.enums.PaymentStatus;
import io.github.davigdomingues.paymenthub.domain.enums.RiskLevel;
import io.github.davigdomingues.paymenthub.domain.model.PaymentContext;
import io.github.davigdomingues.paymenthub.domain.model.PaymentExecutionResult;
import io.github.davigdomingues.paymenthub.domain.model.RiskAssessment;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * Entidade JPA central que representa uma transação persistida no Payment Hub.
 *
 * Estrutura e ponto de maior impacto: agrega o estado financeiro, a classificação de risco e a
 * rastreabilidade dos padrões executados em uma única tabela ({@code payment_transactions}). As
 * restrições {@code unique} em {@code transaction_code} e {@code idempotency_key} atuam como última
 * linha de defesa contra duplicidade de cobrança no banco, enquanto
 * {@code precision = 19, scale = 2} previne perda de precisão monetária. Fora os timestamps
 * gerenciados por callbacks JPA, a única mutação permitida após a criação ocorre via
 * {@link #markAsRefunded(String)}.
 */
@Entity
@Table(name = "payment_transactions")
public class PaymentTransaction {

    private static final String LIST_DELIMITER = ",";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // Índice único garante rastreabilidade 1:1 com o código emitido pelo Singleton
    @Column(name = "transaction_code", nullable = false, unique = true, length = 40)
    private String transactionCode;

    // Chave opcional única no banco para impedir processamento duplicado em retentativas de rede
    @Column(name = "idempotency_key", unique = true, length = 100)
    private String idempotencyKey;

    @Column(name = "payer_document", nullable = false, length = 20)
    private String payerDocument;

    @Column(name = "payee_document", nullable = false, length = 20)
    private String payeeDocument;

    @Enumerated(EnumType.STRING)
    @Column(name = "payment_method", nullable = false, length = 20)
    private PaymentMethod paymentMethod;

    @Enumerated(EnumType.STRING)
    @Column(name = "customer_tier", nullable = false, length = 20)
    private CustomerTier customerTier;

    @Column(name = "installments", nullable = false)
    private int installments;

    @Column(name = "gross_amount", nullable = false, precision = 19, scale = 2)
    private BigDecimal grossAmount;

    @Column(name = "fee_amount", nullable = false, precision = 19, scale = 2)
    private BigDecimal feeAmount;

    @Column(name = "cashback_amount", nullable = false, precision = 19, scale = 2)
    private BigDecimal cashbackAmount;

    @Column(name = "net_amount", nullable = false, precision = 19, scale = 2)
    private BigDecimal netAmount;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 30)
    private PaymentStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "risk_level", nullable = false, length = 20)
    private RiskLevel riskLevel;

    @Column(name = "risk_score", nullable = false)
    private int riskScore;

    @Column(name = "risk_flags", length = 500)
    private String riskFlags;

    @Column(name = "external_authorization_code", length = 64)
    private String externalAuthorizationCode;

    @Column(name = "clearing_channel", length = 64)
    private String clearingChannel;

    @Column(name = "settlement_window", length = 120)
    private String settlementWindow;

    @Column(name = "applied_decorators", length = 300)
    private String appliedDecorators;

    @Column(name = "processing_time_ms", nullable = false)
    private long processingTimeMs;

    @Column(name = "description", length = 255)
    private String description;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /**
     * Construtor padrão exigido pela especificação JPA.
     */
    protected PaymentTransaction() {
    }

    private PaymentTransaction(
            PaymentContext context,
            BigDecimal feeAmount,
            BigDecimal cashbackAmount,
            BigDecimal netAmount,
            PaymentStatus status,
            RiskAssessment riskAssessment,
            String externalAuthorizationCode,
            String clearingChannel,
            String settlementWindow,
            List<String> appliedDecorators,
            long processingTimeMs
    ) {
        this.transactionCode = context.transactionCode();
        this.idempotencyKey = context.idempotencyKey();
        this.payerDocument = context.payerDocument();
        this.payeeDocument = context.payeeDocument();
        this.paymentMethod = context.paymentMethod();
        this.customerTier = context.customerTier();
        this.installments = context.installments();
        this.grossAmount = context.amount();
        this.feeAmount = feeAmount.setScale(2, RoundingMode.HALF_EVEN);
        this.cashbackAmount = cashbackAmount.setScale(2, RoundingMode.HALF_EVEN);
        this.netAmount = netAmount.setScale(2, RoundingMode.HALF_EVEN);
        this.status = status;
        this.riskLevel = riskAssessment.riskLevel();
        this.riskScore = riskAssessment.riskScore();
        this.riskFlags = serializeList(riskAssessment.flags());
        this.externalAuthorizationCode = externalAuthorizationCode;
        this.clearingChannel = clearingChannel;
        this.settlementWindow = settlementWindow;
        this.appliedDecorators = serializeList(appliedDecorators);
        this.processingTimeMs = processingTimeMs;
        this.description = context.description();
    }

    /**
     * Constrói uma entidade persistível a partir de um resultado concluído pelo pipeline.
     *
     * @param context contexto da requisição de pagamento
     * @param result resultado consolidado pelo pipeline decorado
     * @return entidade pronta para persistência
     * @throws NullPointerException quando {@code context} ou {@code result} forem nulos
     */
    public static PaymentTransaction fromExecutionResult(PaymentContext context, PaymentExecutionResult result) {
        Objects.requireNonNull(context, "context não pode ser nulo");
        Objects.requireNonNull(result, "result não pode ser nulo");
        return new PaymentTransaction(
                context,
                result.feeAmount(),
                result.cashbackAmount(),
                result.netAmount(),
                result.status(),
                result.riskAssessment(),
                result.externalAuthorizationCode(),
                result.clearingChannel(),
                result.settlementWindow(),
                result.appliedDecorators(),
                result.processingTimeMs()
        );
    }

    /**
     * Constrói uma entidade marcada como rejeitada durante a avaliação de compliance.
     *
     * Mantém valores financeiros zerados e {@code clearingChannel = "COMPLIANCE_GATE"}, permitindo
     * rastreamento de tentativas bloqueadas sem acionar o adaptador legado.
     *
     * @param context contexto da requisição de pagamento
     * @param riskAssessment avaliação bloqueante emitida pela Chain of Responsibility
     * @return entidade com status {@link PaymentStatus#REJECTED}
     * @throws NullPointerException quando {@code context} ou {@code riskAssessment} forem nulos
     */
    public static PaymentTransaction rejected(PaymentContext context, RiskAssessment riskAssessment) {
        Objects.requireNonNull(context, "context não pode ser nulo");
        Objects.requireNonNull(riskAssessment, "riskAssessment não pode ser nulo");
        BigDecimal zero = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_EVEN);
        return new PaymentTransaction(
                context,
                zero,
                zero,
                zero,
                PaymentStatus.REJECTED,
                riskAssessment,
                null,
                "COMPLIANCE_GATE",
                "Rejeitado antes da compensação",
                List.of(),
                0L
        );
    }

    /**
     * Única transição de estado pós-persistência permitida na entidade: move a transação para
     * {@link PaymentStatus#REFUNDED} após confirmação de estorno no canal legado.
     *
     * @param reversalAuthCode código de autorização do estorno retornado pelo adaptador
     */
    public void markAsRefunded(String reversalAuthCode) {
        this.status = PaymentStatus.REFUNDED;
        if (reversalAuthCode != null && !reversalAuthCode.isBlank()) {
            this.externalAuthorizationCode = reversalAuthCode;
        }
        this.updatedAt = Instant.now();
    }

    /**
     * Retorna a lista imutável de flags de risco registradas na transação.
     *
     * @return lista de flags de compliance
     */
    public List<String> getRiskFlagsList() {
        return deserializeList(riskFlags);
    }

    /**
     * Retorna a lista imutável de decorators aplicados durante o processamento.
     *
     * @return lista ordenada de nomes de decorators
     */
    public List<String> getAppliedDecoratorsList() {
        return deserializeList(appliedDecorators);
    }

    // Callbacks JPA garantem carimbos temporais consistentes mesmo quando persistida fora do Facade
    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        if (this.createdAt == null) {
            this.createdAt = now;
        }
        this.updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        this.updatedAt = Instant.now();
    }

    // Serialização inline delimitada por vírgula evita tabelas auxiliares mantendo consulta simples no H2
    private static String serializeList(List<String> items) {
        if (items == null || items.isEmpty()) {
            return "";
        }
        return String.join(LIST_DELIMITER, items);
    }

    private static List<String> deserializeList(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        return Arrays.stream(raw.split(LIST_DELIMITER))
                .map(String::trim)
                .filter(item -> !item.isEmpty())
                .toList();
    }

    public Long getId() {
        return id;
    }

    public String getTransactionCode() {
        return transactionCode;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public String getPayerDocument() {
        return payerDocument;
    }

    public String getPayeeDocument() {
        return payeeDocument;
    }

    public PaymentMethod getPaymentMethod() {
        return paymentMethod;
    }

    public CustomerTier getCustomerTier() {
        return customerTier;
    }

    public int getInstallments() {
        return installments;
    }

    public BigDecimal getGrossAmount() {
        return grossAmount;
    }

    public BigDecimal getFeeAmount() {
        return feeAmount;
    }

    public BigDecimal getCashbackAmount() {
        return cashbackAmount;
    }

    public BigDecimal getNetAmount() {
        return netAmount;
    }

    public PaymentStatus getStatus() {
        return status;
    }

    public RiskLevel getRiskLevel() {
        return riskLevel;
    }

    public int getRiskScore() {
        return riskScore;
    }

    public String getRiskFlags() {
        return riskFlags;
    }

    public String getExternalAuthorizationCode() {
        return externalAuthorizationCode;
    }

    public String getClearingChannel() {
        return clearingChannel;
    }

    public String getSettlementWindow() {
        return settlementWindow;
    }

    public String getAppliedDecorators() {
        return appliedDecorators;
    }

    public long getProcessingTimeMs() {
        return processingTimeMs;
    }

    public String getDescription() {
        return description;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
