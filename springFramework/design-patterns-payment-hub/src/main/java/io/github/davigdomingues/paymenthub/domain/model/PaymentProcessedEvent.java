package io.github.davigdomingues.paymenthub.domain.model;

import io.github.davigdomingues.paymenthub.domain.enums.PaymentMethod;
import io.github.davigdomingues.paymenthub.domain.enums.PaymentStatus;
import io.github.davigdomingues.paymenthub.domain.enums.RiskLevel;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;

/**
 * Evento de domínio imutável emitido pelo Publisher do padrão Observer após processamento, rejeição
 * ou estorno de uma transação.
 *
 * Estrutura e impacto: desacopla o núcleo transacional do Facade dos efeitos colaterais de
 * auditoria ({@code AuditLogObserver}), notificações multicanal
 * ({@code NotificationDispatchObserver}) e telemetria de negócio
 * ({@code MetricsCollectorObserver}). A imutabilidade impede que um observador corrompa o payload
 * recebido pelos demais.
 */
public record PaymentProcessedEvent(
        String transactionCode,
        String eventType,
        PaymentMethod paymentMethod,
        PaymentStatus status,
        BigDecimal grossAmount,
        BigDecimal feeAmount,
        BigDecimal cashbackAmount,
        String payerDocument,
        String payeeDocument,
        RiskLevel riskLevel,
        Instant occurredAt,
        String details
) {
    /**
     * Valida os atributos estruturais mínimos exigidos pelos observadores inscritos.
     *
     * @throws NullPointerException quando {@code transactionCode}, {@code eventType},
     *     {@code paymentMethod}, {@code status} ou {@code riskLevel} forem nulos
     */
    public PaymentProcessedEvent {
        Objects.requireNonNull(transactionCode, "transactionCode não pode ser nulo");
        Objects.requireNonNull(eventType, "eventType não pode ser nulo");
        Objects.requireNonNull(paymentMethod, "paymentMethod não pode ser nulo");
        Objects.requireNonNull(status, "status não pode ser nulo");
        Objects.requireNonNull(riskLevel, "riskLevel não pode ser nulo");
        occurredAt = occurredAt != null ? occurredAt : Instant.now();
    }
}
