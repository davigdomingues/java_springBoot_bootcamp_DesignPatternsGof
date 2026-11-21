package io.github.davigdomingues.paymenthub.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.Objects;

/**
 * Registro persistente de notificações multicanal disparadas pelo
 * {@code NotificationDispatchObserver}.
 *
 * Estrutura e impacto: armazena evidências de envio nos canais {@code EMAIL}, {@code PUSH} e
 * {@code FRAUD_ALERT_WEBHOOK} na tabela {@code notification_logs}. Assim como a auditoria, alertas
 * antifraude em bloqueios de compliance dependem do
 * {@code noRollbackFor = ComplianceViolationException.class} para permanecerem gravados.
 */
@Entity
@Table(name = "notification_logs")
public class NotificationLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "transaction_code", nullable = false, length = 40)
    private String transactionCode;

    @Column(name = "channel", nullable = false, length = 40)
    private String channel;

    @Column(name = "recipient", nullable = false, length = 120)
    private String recipient;

    @Column(name = "subject", nullable = false, length = 180)
    private String subject;

    @Column(name = "message", nullable = false, length = 500)
    private String message;

    // Timestamp imutável indicando o instante exato de emissão do evento
    @Column(name = "dispatched_at", nullable = false, updatable = false)
    private Instant dispatchedAt;

    protected NotificationLog() {
    }

    /**
     * Cria um registro de notificação validando metadados mínimos de despacho.
     *
     * @throws NullPointerException quando {@code transactionCode}, {@code channel},
     *     {@code recipient} ou {@code subject} forem nulos
     */
    public NotificationLog(
            String transactionCode,
            String channel,
            String recipient,
            String subject,
            String message,
            Instant dispatchedAt
    ) {
        this.transactionCode = Objects.requireNonNull(transactionCode, "transactionCode não pode ser nulo");
        this.channel = Objects.requireNonNull(channel, "channel não pode ser nulo");
        this.recipient = Objects.requireNonNull(recipient, "recipient não pode ser nulo");
        this.subject = Objects.requireNonNull(subject, "subject não pode ser nulo");
        this.message = message != null ? message : "";
        this.dispatchedAt = dispatchedAt != null ? dispatchedAt : Instant.now();
    }

    public Long getId() {
        return id;
    }

    public String getTransactionCode() {
        return transactionCode;
    }

    public String getChannel() {
        return channel;
    }

    public String getRecipient() {
        return recipient;
    }

    public String getSubject() {
        return subject;
    }

    public String getMessage() {
        return message;
    }

    public Instant getDispatchedAt() {
        return dispatchedAt;
    }
}
