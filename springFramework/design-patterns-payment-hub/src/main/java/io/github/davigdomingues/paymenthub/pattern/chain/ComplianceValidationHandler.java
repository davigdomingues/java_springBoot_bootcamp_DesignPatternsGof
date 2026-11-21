package io.github.davigdomingues.paymenthub.pattern.chain;

import io.github.davigdomingues.paymenthub.domain.model.PaymentContext;
import io.github.davigdomingues.paymenthub.domain.model.RiskAssessment;

import java.util.Objects;

/**
 * Padrão GoF: Chain of Responsibility (Handler Base + Template Method)
 *
 * Estrutura e ponto de maior impacto: define o esqueleto imutável ({@code final}) da cadeia de
 * compliance e antifraude. Cada elo recebe o {@link RiskAssessment} acumulado, produz uma nova
 * instância imutável via {@link #evaluate(PaymentContext, RiskAssessment)} e aplica curto-circuito
 * imediato quando {@code !updated.approved()}, evitando consultas custosas ao banco em elos
 * posteriores (como controle de velocidade) após bloqueios determinísticos prévios.
 */
public abstract class ComplianceValidationHandler {

    private ComplianceValidationHandler next;

    /**
     * Encadeia o próximo validador após este elo.
     *
     * @param next próximo handler da cadeia
     * @return o próprio {@code next}, permitindo encadeamento fluente
     *     {@code a.linkWith(b).linkWith(c)}
     * @throws NullPointerException quando {@code next} for nulo
     */
    public ComplianceValidationHandler linkWith(ComplianceValidationHandler next) {
        this.next = Objects.requireNonNull(next, "next handler não pode ser nulo");
        return next;
    }

    /**
     * Inicia a execução da cadeia a partir de uma avaliação de risco limpa.
     *
     * @param context contexto imutável do pagamento
     * @return avaliação consolidada da cadeia
     * @throws NullPointerException quando {@code context} for nulo
     */
    public RiskAssessment validate(PaymentContext context) {
        return validate(context, RiskAssessment.clean());
    }

    /**
     * Template Method que executa este elo e propaga a avaliação acumulada para o próximo elo
     * somente enquanto a operação permanecer aprovada.
     *
     * @param context contexto imutável do pagamento
     * @param current avaliação acumulada pelos elos anteriores
     * @return avaliação atualizada
     * @throws NullPointerException quando {@code context} ou {@code current} forem nulos
     */
    public final RiskAssessment validate(PaymentContext context, RiskAssessment current) {
        Objects.requireNonNull(context, "context não pode ser nulo");
        Objects.requireNonNull(current, "current RiskAssessment não pode ser nulo");

        RiskAssessment updated = evaluate(context, current);
        // Curto-circuito impede chamadas subsequentes (inclusive I/O de banco) após primeiro bloqueio
        if (!updated.approved() || next == null) {
            return updated;
        }
        return next.validate(context, updated);
    }

    /**
     * Implementa a regra específica de compliance deste elo da cadeia.
     *
     * @param context contexto do pagamento
     * @param current avaliação acumulada até o momento
     * @return nova avaliação resultante da análise deste elo
     */
    protected abstract RiskAssessment evaluate(PaymentContext context, RiskAssessment current);
}
