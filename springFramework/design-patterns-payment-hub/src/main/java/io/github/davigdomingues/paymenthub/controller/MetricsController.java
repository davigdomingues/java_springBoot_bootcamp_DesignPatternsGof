package io.github.davigdomingues.paymenthub.controller;

import io.github.davigdomingues.paymenthub.dto.response.MetricsSummaryResponse;
import io.github.davigdomingues.paymenthub.pattern.observer.MetricsCollectorObserver;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Objects;

/**
 * Controlador REST somente leitura para exposição das métricas consolidadas pelo
 * {@link MetricsCollectorObserver}.
 *
 * Estrutura e impacto: lê o snapshot thread-safe mantido em memória pelo observador de métricas sem
 * executar agregações pesadas diretamente no banco H2.
 */
@RestController
@RequestMapping("/api/v1/metrics")
@Tag(name = "Métricas", description = "Métricas agregadas em tempo real pelo Observer")
public class MetricsController {

    private final MetricsCollectorObserver metricsCollectorObserver;

    /**
     * @throws NullPointerException quando {@code metricsCollectorObserver} for nulo
     */
    public MetricsController(MetricsCollectorObserver metricsCollectorObserver) {
        this.metricsCollectorObserver = Objects.requireNonNull(
                metricsCollectorObserver,
                "metricsCollectorObserver não pode ser nulo"
        );
    }

    @GetMapping("/summary")
    @Operation(summary = "Obter resumo agregado das métricas de pagamento")
    public ResponseEntity<MetricsSummaryResponse> getSummary() {
        return ResponseEntity.ok(metricsCollectorObserver.getSummary());
    }
}
