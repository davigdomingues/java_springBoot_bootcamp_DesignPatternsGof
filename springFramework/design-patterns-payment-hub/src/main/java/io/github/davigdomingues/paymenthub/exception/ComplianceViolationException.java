package io.github.davigdomingues.paymenthub.exception;

import java.util.List;
import java.util.Objects;

/**
 * Exceção lançada pelo {@code PaymentOrchestrationFacade} quando a Chain of Responsibility de
 * compliance bloqueia a transação ({@code riskAssessment.approved() == false}).
 *
 * Ponto de maior impacto: carrega o {@code transactionCode} já persistido como {@code REJECTED} e a
 * lista imutável de {@code violations}. Como o método {@code processPayment} configura
 * {@code @Transactional(noRollbackFor = ComplianceViolationException.class)}, o lançamento desta
 * exceção devolve HTTP {@code 422 Unprocessable Entity} ao cliente sem desfazer o commit da
 * transação bloqueada, da auditoria e do alerta antifraude no H2.
 */
public class ComplianceViolationException extends RuntimeException {

    private final String transactionCode;
    private final List<String> violations;

    /**
     * Constrói a exceção preservando o identificador da transação rejeitada e suas violações.
     *
     * @param transactionCode código gerado pelo Singleton e persistido em {@code REJECTED}
     * @param violations flags bloqueantes emitidas pela cadeia de compliance
     * @throws NullPointerException quando {@code transactionCode} for nulo
     */
    public ComplianceViolationException(String transactionCode, List<String> violations) {
        super("Pagamento bloqueado pela validação de compliance para transactionCode=" + transactionCode);
        this.transactionCode = Objects.requireNonNull(transactionCode, "transactionCode não pode ser nulo");
        // Cópia defensiva protege a lista de violações na serialização do payload HTTP 422
        this.violations = violations == null ? List.of() : List.copyOf(violations);
    }

    public String getTransactionCode() {
        return transactionCode;
    }

    public List<String> getViolations() {
        return violations;
    }
}
