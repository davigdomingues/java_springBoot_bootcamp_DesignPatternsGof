package io.github.davigdomingues.paymenthub.pattern.observer;

import io.github.davigdomingues.paymenthub.domain.model.PaymentProcessedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Padrão GoF: Observer (Subject / Event Publisher)
 *
 * Estrutura e ponto de maior impacto: mantém os observadores em {@link CopyOnWriteArrayList} com
 * registro idempotente ({@code addIfAbsent}), garantindo iteração lock-free segura mesmo sob
 * inscrições/remoções concorrentes em runtime. O ponto crítico de resiliência reside em
 * {@link #notifyObservers(PaymentProcessedEvent)}: um bloco {@code try/catch (RuntimeException)}
 * isola cada observador para que a falha de um canal (ex.: disparo de notificação) nunca impeça a
 * gravação da auditoria ou a atualização de métricas, nem cause rollback inesperado no Facade.
 */
@Component
public class PaymentEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(PaymentEventPublisher.class);

    // CopyOnWriteArrayList evita ConcurrentModificationException durante leitura concorrente à inscrição
    private final CopyOnWriteArrayList<PaymentEventObserver> observers;

    public PaymentEventPublisher(List<PaymentEventObserver> observerBeans) {
        this.observers = new CopyOnWriteArrayList<>();
        if (observerBeans != null) {
            observerBeans.forEach(this::subscribe);
        }
    }

    /**
     * Inscreve um novo observador de forma idempotente caso ainda não esteja registrado.
     *
     * @param observer instância do observador
     * @throws NullPointerException quando {@code observer} for nulo
     */
    public void subscribe(PaymentEventObserver observer) {
        Objects.requireNonNull(observer, "observer não pode ser nulo");
        observers.addIfAbsent(observer);
    }

    /**
     * Remove um observador da lista de assinantes.
     *
     * @param observer instância do observador a remover
     */
    public void unsubscribe(PaymentEventObserver observer) {
        if (observer != null) {
            observers.remove(observer);
        }
    }

    /**
     * Notifica todos os observadores inscritos isolando exceções individuais de execução.
     *
     * @param event evento imutável ocorrido
     * @throws NullPointerException quando {@code event} for nulo
     */
    public void notifyObservers(PaymentProcessedEvent event) {
        Objects.requireNonNull(event, "event não pode ser nulo");
        for (PaymentEventObserver observer : observers) {
            try {
                observer.onPaymentEvent(event);
            } catch (RuntimeException ex) {
                // Isolamento de falha: erro em um assinante é logado sem abortar auditoria nem métricas
                log.error(
                        "Falha no observador observer={} transactionCode={} eventType={}",
                        observer.observerName(),
                        event.transactionCode(),
                        event.eventType(),
                        ex
                );
            }
        }
    }

    /**
     * Lista os nomes dos observadores atualmente inscritos.
     *
     * @return lista imutável de nomes de observadores
     */
    public List<String> registeredObservers() {
        return observers.stream()
                .map(PaymentEventObserver::observerName)
                .toList();
    }
}
