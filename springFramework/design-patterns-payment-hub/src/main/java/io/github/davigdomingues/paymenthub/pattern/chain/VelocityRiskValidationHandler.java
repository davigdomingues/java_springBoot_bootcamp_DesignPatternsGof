package io.github.davigdomingues.paymenthub.pattern.chain;

import io.github.davigdomingues.paymenthub.domain.model.PaymentContext;
import io.github.davigdomingues.paymenthub.domain.model.RiskAssessment;
import io.github.davigdomingues.paymenthub.repository.PaymentTransactionRepository;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;

/**
 * Padrão GoF: Chain of Responsibility (Elo 3 - Controle de Velocidade)
 *
 * Estrutura e ponto de maior impacto: posicionado como último elo da cadeia por ser o único que
 * executa leitura em banco ({@link PaymentTransactionRepository}), beneficiando-se do
 * curto-circuito dos elos anteriores. Consulta a quantidade de transações do pagador na janela
 * deslizante configurada (padrão 5 minutos): bloqueia em {@code >= blockThreshold}
 * ({@code VELOCITY_LIMIT_EXCEEDED}) e adiciona {@code +30} pontos ({@code ELEVATED_VELOCITY}) em
 * {@code >= alertThreshold}, acumulando com eventuais alertas prévios (ex.:
 * {@code HIGH_VALUE_TRANSACTION} + {@code ELEVATED_VELOCITY} = 65 -> {@code HIGH}).
 */
public class VelocityRiskValidationHandler extends ComplianceValidationHandler {

    private static final long DEFAULT_WINDOW_MINUTES = 5L;
    private static final int DEFAULT_ALERT_THRESHOLD = 3;
    private static final int DEFAULT_BLOCK_THRESHOLD = 5;
    private static final int ELEVATED_VELOCITY_RISK_POINTS = 30;

    private final PaymentTransactionRepository transactionRepository;
    private final long windowMinutes;
    private final int alertThreshold;
    private final int blockThreshold;

    public VelocityRiskValidationHandler(PaymentTransactionRepository transactionRepository) {
        this(transactionRepository, DEFAULT_WINDOW_MINUTES, DEFAULT_ALERT_THRESHOLD, DEFAULT_BLOCK_THRESHOLD);
    }

    /**
     * Inicializa o validador de velocidade com janela e limiares normalizados.
     *
     * @throws NullPointerException quando {@code transactionRepository} for nulo
     */
    public VelocityRiskValidationHandler(
            PaymentTransactionRepository transactionRepository,
            long windowMinutes,
            int alertThreshold,
            int blockThreshold) {
        this.transactionRepository = Objects.requireNonNull(
                transactionRepository,
                "transactionRepository não pode ser nulo");
        this.windowMinutes = Math.max(1L, windowMinutes);
        this.alertThreshold = Math.max(1, alertThreshold);
        this.blockThreshold = Math.max(this.alertThreshold, blockThreshold);
    }

    @Override
    protected RiskAssessment evaluate(PaymentContext context, RiskAssessment current) {
        Instant windowStart = Instant.now().minus(windowMinutes, ChronoUnit.MINUTES);
        long recentCount = transactionRepository.countByPayerDocumentAndCreatedAtAfter(
                context.payerDocument(),
                windowStart);

        if (recentCount >= blockThreshold) {
            // Preserva flags anteriores acumuladas (ex.: HIGH_VALUE_TRANSACTION) antes de bloquear
            return current.block("VELOCITY_LIMIT_EXCEEDED");
        }
        if (recentCount >= alertThreshold) {
            return current.withAddedRisk(ELEVATED_VELOCITY_RISK_POINTS, "ELEVATED_VELOCITY");
        }
        return current;
    }
}
