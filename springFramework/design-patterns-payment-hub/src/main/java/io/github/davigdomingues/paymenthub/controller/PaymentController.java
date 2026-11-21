package io.github.davigdomingues.paymenthub.controller;

import io.github.davigdomingues.paymenthub.domain.enums.PaymentMethod;
import io.github.davigdomingues.paymenthub.domain.enums.PaymentStatus;
import io.github.davigdomingues.paymenthub.dto.request.CreatePaymentRequest;
import io.github.davigdomingues.paymenthub.dto.response.ApiErrorResponse;
import io.github.davigdomingues.paymenthub.dto.response.AuditLogResponse;
import io.github.davigdomingues.paymenthub.dto.response.NotificationResponse;
import io.github.davigdomingues.paymenthub.dto.response.PaymentResponse;
import io.github.davigdomingues.paymenthub.dto.response.PaymentSimulationResponse;
import io.github.davigdomingues.paymenthub.pattern.facade.PaymentOrchestrationFacade;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Objects;

/**
 * Controlador REST fino para processamento, simulação, consulta, estorno, auditoria e notificações
 * de pagamentos.
 *
 * Estrutura e impacto: atua exclusivamente como adaptador HTTP na fronteira da aplicação, aplicando
 * Bean Validation ({@code @Valid}) nos payloads de entrada e delegando toda regra de negócio ao
 * {@link PaymentOrchestrationFacade}. Erros sintáticos são convertidos em HTTP {@code 400},
 * ausência de recursos em {@code 404} e violações de negócio/compliance em {@code 422} pelo
 * {@code GlobalExceptionHandler}.
 */
@RestController
@RequestMapping("/api/v1/payments")
@Tag(name = "Pagamentos", description = "Orquestração e consulta de pagamentos via Facade")
public class PaymentController {

    private final PaymentOrchestrationFacade paymentFacade;

    /**
     * @throws NullPointerException quando {@code paymentFacade} for nulo
     */
    public PaymentController(PaymentOrchestrationFacade paymentFacade) {
        this.paymentFacade = Objects.requireNonNull(paymentFacade, "paymentFacade não pode ser nulo");
    }

    @PostMapping
    @Operation(
            summary = "Processar uma transação de pagamento",
            description = "Executa validação estratégica, análise de compliance, liquidação legada, cashback, telemetria e observadores."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Pagamento processado ou recuperado por chave de idempotência"),
            @ApiResponse(
                    responseCode = "400",
                    description = "Erro de validação ou payload malformado",
                    content = @Content(schema = @Schema(implementation = ApiErrorResponse.class))
            ),
            @ApiResponse(
                    responseCode = "422",
                    description = "Violação de regra de negócio ou de compliance",
                    content = @Content(schema = @Schema(implementation = ApiErrorResponse.class))
            )
    })
    public ResponseEntity<PaymentResponse> createPayment(@Valid @RequestBody CreatePaymentRequest request) {
        PaymentResponse response = paymentFacade.processPayment(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @PostMapping("/simulate")
    @Operation(
            summary = "Simular tarifas, cashback e análise de risco do pagamento",
            description = "Executa cálculos de Strategy, cashback e Chain of Responsibility sem persistir nem liquidar."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Simulação calculada com sucesso"),
            @ApiResponse(
                    responseCode = "400",
                    description = "Erro de validação",
                    content = @Content(schema = @Schema(implementation = ApiErrorResponse.class))
            ),
            @ApiResponse(
                    responseCode = "422",
                    description = "Violação de regra de negócio",
                    content = @Content(schema = @Schema(implementation = ApiErrorResponse.class))
            )
    })
    public ResponseEntity<PaymentSimulationResponse> simulatePayment(@Valid @RequestBody CreatePaymentRequest request) {
        return ResponseEntity.ok(paymentFacade.simulatePayment(request));
    }

    @GetMapping
    @Operation(summary = "Listar transações de pagamento com filtros opcionais")
    public ResponseEntity<List<PaymentResponse>> listPayments(
            @RequestParam(required = false) PaymentMethod method,
            @RequestParam(required = false) PaymentStatus status
    ) {
        return ResponseEntity.ok(paymentFacade.listPayments(method, status));
    }

    @GetMapping("/{transactionCode}")
    @Operation(summary = "Consultar uma transação de pagamento pelo código único")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Transação encontrada"),
            @ApiResponse(
                    responseCode = "404",
                    description = "Transação não encontrada",
                    content = @Content(schema = @Schema(implementation = ApiErrorResponse.class))
            )
    })
    public ResponseEntity<PaymentResponse> getPayment(@PathVariable String transactionCode) {
        return ResponseEntity.ok(paymentFacade.getByTransactionCode(transactionCode));
    }

    @PostMapping("/{transactionCode}/refund")
    @Operation(summary = "Estornar um pagamento aprovado ou pendente de liquidação")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Pagamento estornado com sucesso"),
            @ApiResponse(
                    responseCode = "404",
                    description = "Transação não encontrada",
                    content = @Content(schema = @Schema(implementation = ApiErrorResponse.class))
            ),
            @ApiResponse(
                    responseCode = "422",
                    description = "Status da transação não é elegível para estorno",
                    content = @Content(schema = @Schema(implementation = ApiErrorResponse.class))
            )
    })
    public ResponseEntity<PaymentResponse> refundPayment(@PathVariable String transactionCode) {
        return ResponseEntity.ok(paymentFacade.refundPayment(transactionCode));
    }

    @GetMapping("/{transactionCode}/audit")
    @Operation(summary = "Consultar a trilha de auditoria de uma transação")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Registros de auditoria recuperados"),
            @ApiResponse(
                    responseCode = "404",
                    description = "Transação não encontrada",
                    content = @Content(schema = @Schema(implementation = ApiErrorResponse.class))
            )
    })
    public ResponseEntity<List<AuditLogResponse>> getAuditTrail(@PathVariable String transactionCode) {
        return ResponseEntity.ok(paymentFacade.getAuditTrail(transactionCode));
    }

    @GetMapping("/{transactionCode}/notifications")
    @Operation(summary = "Consultar notificações disparadas para uma transação")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Registros de notificação recuperados"),
            @ApiResponse(
                    responseCode = "404",
                    description = "Transação não encontrada",
                    content = @Content(schema = @Schema(implementation = ApiErrorResponse.class))
            )
    })
    public ResponseEntity<List<NotificationResponse>> getNotifications(@PathVariable String transactionCode) {
        return ResponseEntity.ok(paymentFacade.getNotifications(transactionCode));
    }
}
