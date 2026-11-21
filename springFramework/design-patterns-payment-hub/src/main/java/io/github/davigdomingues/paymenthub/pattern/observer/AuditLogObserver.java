package io.github.davigdomingues.paymenthub.pattern.observer;

import io.github.davigdomingues.paymenthub.domain.entity.PaymentAuditLog;
import io.github.davigdomingues.paymenthub.domain.model.PaymentProcessedEvent;
import io.github.davigdomingues.paymenthub.repository.PaymentAuditLogRepository;
import org.springframework.stereotype.Component;

import java.util.Objects;

/**
 * Padrão GoF: Observer (Concrete Observer - Trilha de Auditoria)
 *
 * Estrutura e impacto: reage a todos os eventos emitidos pelo {@link PaymentEventPublisher}
 * gravando um registro append-only em {@code payment_audit_logs}. Sua atuação conjunta com
 * {@code noRollbackFor = ComplianceViolationException.class} no Facade assegura que até mesmo
 * bloqueios de compliance fiquem auditáveis em banco.
 */
@Component
public class AuditLogObserver implements PaymentEventObserver {

    private final PaymentAuditLogRepository auditLogRepository;

    /**
     * @throws NullPointerException quando {@code auditLogRepository} for nulo
     */
    public AuditLogObserver(PaymentAuditLogRepository auditLogRepository) {
        this.auditLogRepository = Objects.requireNonNull(auditLogRepository, "auditLogRepository não pode ser nulo");
    }

    @Override
    public String observerName() {
        return "AuditLogObserver";
    }

    @Override
    public void onPaymentEvent(PaymentProcessedEvent event) {
        PaymentAuditLog entry = new PaymentAuditLog(
                event.transactionCode(),
                event.eventType(),
                event.status(),
                event.riskLevel(),
                event.details(),
                event.occurredAt()
        );
        auditLogRepository.save(entry);
    }
}
