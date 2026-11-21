package io.github.davigdomingues.paymenthub.pattern.observer;

import io.github.davigdomingues.paymenthub.domain.entity.NotificationLog;
import io.github.davigdomingues.paymenthub.domain.enums.PaymentStatus;
import io.github.davigdomingues.paymenthub.domain.model.PaymentProcessedEvent;
import io.github.davigdomingues.paymenthub.repository.NotificationLogRepository;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;

/**
 * Padrão GoF: Observer (Concrete Observer - Despacho de Notificações)
 *
 * Estrutura e ponto de maior impacto: roteia cada evento para dois canais persistidos em
 * {@code notification_logs} conforme o status resultante: em bloqueios de compliance
 * ({@link PaymentStatus#REJECTED}), além de avisar o pagador por {@code EMAIL}, aciona o canal
 * crítico {@code FRAUD_ALERT_WEBHOOK} para a equipe de segurança; nos demais desfechos
 * ({@code APPROVED}, {@code PENDING_SETTLEMENT}, {@code REFUNDED}), dispara {@code PUSH} ao pagador
 * e {@code EMAIL} ao recebedor.
 */
@Component
public class NotificationDispatchObserver implements PaymentEventObserver {

    private final NotificationLogRepository notificationLogRepository;

    /**
     * @throws NullPointerException quando {@code notificationLogRepository} for nulo
     */
    public NotificationDispatchObserver(NotificationLogRepository notificationLogRepository) {
        this.notificationLogRepository = Objects.requireNonNull(
                notificationLogRepository,
                "notificationLogRepository não pode ser nulo"
        );
    }

    @Override
    public String observerName() {
        return "NotificationDispatchObserver";
    }

    @Override
    public void onPaymentEvent(PaymentProcessedEvent event) {
        List<NotificationLog> notifications = buildNotificationsFor(event);
        notificationLogRepository.saveAll(notifications);
    }

    private List<NotificationLog> buildNotificationsFor(PaymentProcessedEvent event) {
        if (event.status() == PaymentStatus.REJECTED) {
            return List.of(
                    new NotificationLog(
                            event.transactionCode(),
                            "EMAIL",
                            "payer:" + event.payerDocument(),
                            "Pagamento bloqueado pela análise de compliance",
                            "A transação " + event.transactionCode() + " foi rejeitada. Detalhes: " + event.details(),
                            event.occurredAt()
                    ),
                    new NotificationLog(
                            event.transactionCode(),
                            "FRAUD_ALERT_WEBHOOK",
                            "security-operations@paymenthub.local",
                            "Alerta de bloqueio de compliance: " + event.transactionCode(),
                            "Pagamento " + event.paymentMethod() + " de " + event.grossAmount()
                                    + " bloqueado para o pagador " + event.payerDocument() + ". Motivo: " + event.details(),
                            event.occurredAt()
                    )
            );
        }

        String subject = event.status() == PaymentStatus.REFUNDED
                ? "Pagamento estornado: " + event.transactionCode()
                : "Atualização de pagamento (" + event.status() + "): " + event.transactionCode();

        String message = "Transação " + event.transactionCode()
                + " via " + event.paymentMethod()
                + " valor " + event.grossAmount()
                + " status " + event.status();

        return List.of(
                new NotificationLog(
                        event.transactionCode(),
                        "PUSH",
                        "payer:" + event.payerDocument(),
                        subject,
                        message,
                        event.occurredAt()
                ),
                new NotificationLog(
                        event.transactionCode(),
                        "EMAIL",
                        "payee:" + event.payeeDocument(),
                        subject,
                        message,
                        event.occurredAt()
                )
        );
    }
}
