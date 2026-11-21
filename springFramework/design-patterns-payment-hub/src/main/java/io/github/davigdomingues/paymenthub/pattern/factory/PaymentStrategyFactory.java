package io.github.davigdomingues.paymenthub.pattern.factory;

import io.github.davigdomingues.paymenthub.domain.enums.PaymentMethod;
import io.github.davigdomingues.paymenthub.exception.UnsupportedPaymentMethodException;
import io.github.davigdomingues.paymenthub.pattern.strategy.PaymentStrategy;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Padrão GoF: Factory Method / Registry
 *
 * Estrutura e ponto de maior impacto: utiliza injeção automática de {@code List<PaymentStrategy>}
 * pelo contêiner Spring para indexar todas as estratégias em um {@link EnumMap} imutável (lookup
 * O(1) sem condicionais {@code if/else}, respeitando o princípio Aberto-Fechado). Falha
 * imediatamente na inicialização em caso de registro duplicado e protege a execução contra métodos
 * ausentes ou nulos.
 */
@Component
public class PaymentStrategyFactory {

    private final Map<PaymentMethod, PaymentStrategy> strategies;

    /**
     * Constrói o registro imutável de estratégias validando unicidade por {@link PaymentMethod}.
     *
     * @param strategyBeans implementações de {@link PaymentStrategy} detectadas no contexto
     * @throws NullPointerException se {@code strategyBeans} ou algum {@code supportedMethod()} for
     *     nulo
     * @throws IllegalStateException se duas estratégias reivindicarem o mesmo {@link PaymentMethod}
     */
    public PaymentStrategyFactory(List<PaymentStrategy> strategyBeans) {
        Objects.requireNonNull(strategyBeans, "strategyBeans não pode ser nulo");
        Map<PaymentMethod, PaymentStrategy> registry = new EnumMap<>(PaymentMethod.class);
        for (PaymentStrategy strategy : strategyBeans) {
            PaymentMethod method = Objects.requireNonNull(
                    strategy.supportedMethod(),
                    "supportedMethod não pode ser nulo para " + strategy.getClass().getSimpleName()
            );
            // Falha fail-fast no boot evita ambiguidade silenciosa entre estratégias concorrentes
            PaymentStrategy previous = registry.put(method, strategy);
            if (previous != null) {
                throw new IllegalStateException("PaymentStrategy duplicada registrada para o método: " + method);
            }
        }
        this.strategies = Collections.unmodifiableMap(registry);
    }

    /**
     * Resolve a estratégia correspondente ao meio de pagamento informado.
     *
     * @param method meio de pagamento desejado
     * @return instância concreta de {@link PaymentStrategy}
     * @throws UnsupportedPaymentMethodException quando {@code method} for nulo ou não registrado
     */
    public PaymentStrategy getStrategy(PaymentMethod method) {
        if (method == null) {
            throw new UnsupportedPaymentMethodException("O método de pagamento não pode ser nulo");
        }
        PaymentStrategy strategy = strategies.get(method);
        if (strategy == null) {
            throw new UnsupportedPaymentMethodException("Nenhuma estratégia registrada para o método de pagamento: " + method);
        }
        return strategy;
    }

    /**
     * Lista todos os meios de pagamento atualmente registrados na fábrica.
     *
     * @return conjunto imutável de métodos suportados
     */
    public Set<PaymentMethod> supportedMethods() {
        return strategies.keySet();
    }
}
