package io.github.davigdomingues.paymenthub.domain.model;

import io.github.davigdomingues.paymenthub.domain.enums.RiskLevel;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Resultado imutável da avaliação de risco e compliance produzida pela Chain of Responsibility.
 *
 * Estrutura e impacto: transporta estado acumulado puro entre os elos da cadeia sem efeito
 * colateral. O campo {@code approved} governa o curto-circuito do
 * {@code ComplianceValidationHandler} e a decisão do {@code PaymentOrchestrationFacade} entre
 * liquidar ou persistir como {@code REJECTED} e lançar {@code ComplianceViolationException}.
 */
public record RiskAssessment(
        int riskScore,
        RiskLevel riskLevel,
        boolean approved,
        List<String> flags
) {
    /**
     * Valida a classificação de risco e realiza cópia defensiva imutável das flags.
     *
     * @throws NullPointerException quando {@code riskLevel} for nulo
     */
    public RiskAssessment {
        Objects.requireNonNull(riskLevel, "riskLevel não pode ser nulo");
        // Cópia defensiva impede alteração externa do histórico de flags após avaliação
        flags = flags == null ? List.of() : List.copyOf(flags);
    }

    /**
     * Cria uma avaliação inicial limpa e aprovada, sem alertas.
     *
     * @return avaliação de risco inicial
     */
    public static RiskAssessment clean() {
        return new RiskAssessment(0, RiskLevel.LOW, true, List.of());
    }

    /**
     * Cria diretamente uma avaliação bloqueada com pontuação máxima e flag justificadora.
     *
     * @param flag motivo do bloqueio
     * @return avaliação bloqueada
     */
    public static RiskAssessment blocked(String flag) {
        List<String> updatedFlags = (flag == null || flag.isBlank()) ? List.of() : List.of(flag.trim());
        return new RiskAssessment(100, RiskLevel.BLOCKED, false, updatedFlags);
    }

    /**
     * Retorna uma nova avaliação acumulando pontos de risco e uma flag informativa, recalculando
     * automaticamente a classificação de risco (limiares: &ge; 30 MEDIUM, &ge; 60 HIGH, teto 100).
     * Se a avaliação já estiver bloqueada, preserva o bloqueio.
     *
     * @param points pontos positivos a adicionar ao score
     * @param flag identificador da ocorrência detectada
     * @return nova instância imutável de avaliação
     */
    public RiskAssessment withAddedRisk(int points, String flag) {
        if (!approved) {
            return block(flag);
        }
        int updatedScore = Math.min(100, Math.max(0, riskScore + Math.max(0, points)));
        RiskLevel updatedLevel = resolveLevel(updatedScore);
        return new RiskAssessment(updatedScore, updatedLevel, true, appendFlag(flag));
    }

    /**
     * Retorna uma nova avaliação bloqueada preservando as flags acumuladas até o momento.
     *
     * @param flag motivo do bloqueio
     * @return avaliação bloqueada com histórico de flags
     */
    public RiskAssessment block(String flag) {
        return new RiskAssessment(100, RiskLevel.BLOCKED, false, appendFlag(flag));
    }

    private List<String> appendFlag(String flag) {
        List<String> updated = new ArrayList<>(flags);
        if (flag != null && !flag.isBlank()) {
            updated.add(flag.trim());
        }
        return List.copyOf(updated);
    }

    private static RiskLevel resolveLevel(int score) {
        if (score >= 60) {
            return RiskLevel.HIGH;
        }
        if (score >= 30) {
            return RiskLevel.MEDIUM;
        }
        return RiskLevel.LOW;
    }
}
