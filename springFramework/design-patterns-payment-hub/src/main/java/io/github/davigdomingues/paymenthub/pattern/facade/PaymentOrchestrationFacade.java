package io.github.davigdomingues.paymenthub.pattern.facade;

import io.github.davigdomingues.paymenthub.domain.entity.PaymentTransaction;
import io.github.davigdomingues.paymenthub.domain.enums.PaymentMethod;
import io.github.davigdomingues.paymenthub.domain.enums.PaymentStatus;
import io.github.davigdomingues.paymenthub.domain.model.PaymentContext;
import io.github.davigdomingues.paymenthub.domain.model.PaymentExecutionResult;
import io.github.davigdomingues.paymenthub.domain.model.PaymentProcessedEvent;
import io.github.davigdomingues.paymenthub.domain.model.RiskAssessment;
import io.github.davigdomingues.paymenthub.dto.request.CreatePaymentRequest;
import io.github.davigdomingues.paymenthub.dto.response.AuditLogResponse;
import io.github.davigdomingues.paymenthub.dto.response.NotificationResponse;
import io.github.davigdomingues.paymenthub.dto.response.PaymentResponse;
import io.github.davigdomingues.paymenthub.dto.response.PaymentSimulationResponse;
import io.github.davigdomingues.paymenthub.exception.BusinessRuleException;
import io.github.davigdomingues.paymenthub.exception.ComplianceViolationException;
import io.github.davigdomingues.paymenthub.exception.ResourceNotFoundException;
import io.github.davigdomingues.paymenthub.exception.UnsupportedPaymentMethodException;
import io.github.davigdomingues.paymenthub.pattern.adapter.ExternalSettlementPort;
import io.github.davigdomingues.paymenthub.pattern.adapter.SettlementResponse;
import io.github.davigdomingues.paymenthub.pattern.chain.ComplianceValidationHandler;
import io.github.davigdomingues.paymenthub.pattern.decorator.CashbackBenefitDecorator;
import io.github.davigdomingues.paymenthub.pattern.decorator.PaymentExecutionPipeline;
import io.github.davigdomingues.paymenthub.pattern.factory.PaymentStrategyFactory;
import io.github.davigdomingues.paymenthub.pattern.observer.PaymentEventPublisher;
import io.github.davigdomingues.paymenthub.pattern.singleton.TransactionCodeGenerator;
import io.github.davigdomingues.paymenthub.pattern.strategy.PaymentStrategy;
import io.github.davigdomingues.paymenthub.repository.NotificationLogRepository;
import io.github.davigdomingues.paymenthub.repository.PaymentAuditLogRepository;
import io.github.davigdomingues.paymenthub.repository.PaymentTransactionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Padrão GoF: Facade
 *
 * Estrutura e ponto de maior impacto: concentra a orquestração de ponta a ponta do Payment Hub
 * atrás de um serviço coeso para os controladores REST, coordenando 8 padrões GoF (Singleton,
 * Factory, Strategy, Chain of Responsibility, Adapter, Builder, Decorator e Observer). Seu aspecto
 * arquitetural mais crítico é a política
 * {@code @Transactional(noRollbackFor = ComplianceViolationException.class)} em
 * {@link #processPayment(CreatePaymentRequest)}, combinada ao curto-circuito de idempotência antes
 * da emissão de código no Singleton e à execução dry-run sem efeitos colaterais em
 * {@link #simulatePayment(CreatePaymentRequest)}.
 */
@Service
public class PaymentOrchestrationFacade {

    private static final String EVENT_PAYMENT_PROCESSED = "PAYMENT_PROCESSED";
    private static final String EVENT_PAYMENT_REJECTED = "PAYMENT_REJECTED";
    private static final String EVENT_PAYMENT_REFUNDED = "PAYMENT_REFUNDED";
    private static final String SIMULATION_CODE = "SIMULATION-PREVIEW";

    private final PaymentStrategyFactory strategyFactory;
    private final ComplianceValidationHandler complianceChain;
    private final PaymentExecutionPipeline executionPipeline;
    private final ExternalSettlementPort settlementPort;
    private final PaymentEventPublisher eventPublisher;
    private final PaymentTransactionRepository transactionRepository;
    private final PaymentAuditLogRepository auditLogRepository;
    private final NotificationLogRepository notificationLogRepository;

    /**
     * Injeta via construtor os coordenadores de padrões GoF e repositórios de persistência.
     *
     * @throws NullPointerException se qualquer colaborador obrigatório for nulo
     */
    public PaymentOrchestrationFacade(
            PaymentStrategyFactory strategyFactory,
            ComplianceValidationHandler complianceChain,
            PaymentExecutionPipeline executionPipeline,
            ExternalSettlementPort settlementPort,
            PaymentEventPublisher eventPublisher,
            PaymentTransactionRepository transactionRepository,
            PaymentAuditLogRepository auditLogRepository,
            NotificationLogRepository notificationLogRepository) {
        this.strategyFactory = Objects.requireNonNull(strategyFactory, "strategyFactory não pode ser nulo");
        this.complianceChain = Objects.requireNonNull(complianceChain, "complianceChain não pode ser nulo");
        this.executionPipeline = Objects.requireNonNull(executionPipeline, "executionPipeline não pode ser nulo");
        this.settlementPort = Objects.requireNonNull(settlementPort, "settlementPort não pode ser nulo");
        this.eventPublisher = Objects.requireNonNull(eventPublisher, "eventPublisher não pode ser nulo");
        this.transactionRepository = Objects.requireNonNull(transactionRepository,
                "transactionRepository não pode ser nulo");
        this.auditLogRepository = Objects.requireNonNull(auditLogRepository, "auditLogRepository não pode ser nulo");
        this.notificationLogRepository = Objects.requireNonNull(notificationLogRepository,
                "notificationLogRepository não pode ser nulo");
    }

    /**
     * Orquestra o fluxo completo de processamento em 8 etapas: (1) checagem de idempotência; (2)
     * geração atômica de código no Singleton; (3) resolução e validação específica na
     * Factory/Strategy; (4) análise na Chain of Responsibility; (5) execução no pipeline decorado
     * (Cashback + Telemetria + Core/Adapter/Builder); (6) persistência JPA; (7) disparo de eventos
     * Observer; e (8) projeção para {@link PaymentResponse}.
     *
     * Exceção crítica de transação: {@code noRollbackFor = ComplianceViolationException.class}
     * impede que o proxy transacional do Spring reverta o commit quando o compliance bloqueia,
     * preservando em H2 a transação {@code REJECTED}, sua auditoria e o
     * {@code FRAUD_ALERT_WEBHOOK}.
     *
     * @param request dados validados da requisição de pagamento
     * @return resumo completo da transação processada ou recuperada por idempotência
     * @throws NullPointerException quando {@code request} for nulo
     * @throws UnsupportedPaymentMethodException quando o método não possuir estratégia
     * @throws BusinessRuleException quando regra da estratégia ou liquidação legada falhar
     * @throws ComplianceViolationException quando bloqueado pela Chain of Responsibility (HTTP 422
     *     com histórico preservado)
     */
    @Transactional(noRollbackFor = ComplianceViolationException.class)
    public PaymentResponse processPayment(CreatePaymentRequest request) {
        Objects.requireNonNull(request, "request não pode ser nulo");

        // Curto-circuito de idempotência antes do Singleton evita desperdício de sequência e dupla cobrança
        String normalizedIdempotencyKey = normalizeBlankToNull(request.idempotencyKey());
        if (normalizedIdempotencyKey != null) {
            Optional<PaymentTransaction> existing = transactionRepository
                    .findByIdempotencyKey(normalizedIdempotencyKey);
            if (existing.isPresent()) {
                return PaymentResponse.fromEntity(existing.get());
            }
        }

        String transactionCode = TransactionCodeGenerator.INSTANCE.nextCode();
        PaymentContext context = buildContext(transactionCode, normalizedIdempotencyKey, request);

        PaymentStrategy strategy = strategyFactory.getStrategy(context.paymentMethod());
        strategy.validateSpecificRules(context);

        RiskAssessment riskAssessment = complianceChain.validate(context);
        if (!riskAssessment.approved()) {
            // Persiste REJECTED e emite eventos antes de lançar ComplianceViolationException (mantido por noRollbackFor)
            PaymentTransaction rejected = transactionRepository
                    .save(PaymentTransaction.rejected(context, riskAssessment));
            publishEvent(
                    rejected,
                    EVENT_PAYMENT_REJECTED,
                    "Bloqueado pelas regras de compliance: " + String.join(", ", riskAssessment.flags()));
            throw new ComplianceViolationException(rejected.getTransactionCode(), riskAssessment.flags());
        }

        PaymentExecutionResult executionResult = executionPipeline.execute(context, riskAssessment);
        PaymentTransaction saved = transactionRepository.save(
                PaymentTransaction.fromExecutionResult(context, executionResult));

        publishEvent(
                saved,
                EVENT_PAYMENT_PROCESSED,
                "Liquidado via " + saved.getClearingChannel() + " auth=" + saved.getExternalAuthorizationCode());

        return PaymentResponse.fromEntity(saved);
    }

    /**
     * Executa simulação prévia (dry-run) em transação somente leitura ({@code readOnly = true}),
     * calculando tarifas, cashback e avaliação de risco sem consumir sequência do Singleton, sem
     * acionar o mainframe legado, sem persistir no H2 e sem notificar observadores.
     *
     * @param request dados da simulação
     * @return projeção financeira e de compliance
     * @throws NullPointerException quando {@code request} for nulo
     * @throws UnsupportedPaymentMethodException quando o método não possuir estratégia
     * @throws BusinessRuleException quando regras da estratégia forem violadas
     */
    @Transactional(readOnly = true)
    public PaymentSimulationResponse simulatePayment(CreatePaymentRequest request) {
        Objects.requireNonNull(request, "request não pode ser nulo");
        // Código sintético evita avançar o contador monotônico do Singleton em simulações
        PaymentContext context = buildContext(SIMULATION_CODE, null, request);

        PaymentStrategy strategy = strategyFactory.getStrategy(context.paymentMethod());
        strategy.validateSpecificRules(context);

        BigDecimal feeAmount = strategy.calculateFee(context).setScale(2, RoundingMode.HALF_EVEN);
        // Reutiliza o cálculo estático do Decorator para manter paridade absoluta com a execução real
        BigDecimal estimatedCashback = CashbackBenefitDecorator.calculateCashback(context);
        BigDecimal netAmount = context.amount().subtract(feeAmount).setScale(2, RoundingMode.HALF_EVEN);

        RiskAssessment riskAssessment = complianceChain.validate(context);
        PaymentStatus projectedStatus = riskAssessment.approved()
                ? strategy.resolveInitialStatus()
                : PaymentStatus.REJECTED;

        return new PaymentSimulationResponse(
                context.paymentMethod(),
                context.customerTier(),
                context.installments(),
                context.amount(),
                feeAmount,
                estimatedCashback,
                netAmount,
                projectedStatus,
                strategy.settlementWindowDescription(),
                riskAssessment.approved(),
                riskAssessment.riskLevel(),
                riskAssessment.riskScore(),
                riskAssessment.flags());
    }

    /**
     * Consulta uma transação pelo código único gerado pelo Singleton.
     *
     * @param transactionCode código da transação
     * @return detalhes completos da transação
     * @throws ResourceNotFoundException quando o código não existir na base
     */
    @Transactional(readOnly = true)
    public PaymentResponse getByTransactionCode(String transactionCode) {
        return PaymentResponse.fromEntity(findTransactionOrThrow(transactionCode));
    }

    /**
     * Lista transações ordenadas da mais recente para a mais antiga, com filtros opcionais.
     *
     * @param paymentMethod filtro opcional por meio de pagamento
     * @param status filtro opcional por status
     * @return lista de transações correspondentes
     */
    @Transactional(readOnly = true)
    public List<PaymentResponse> listPayments(PaymentMethod paymentMethod, PaymentStatus status) {
        List<PaymentTransaction> transactions;
        if (paymentMethod != null && status != null) {
            transactions = transactionRepository.findByPaymentMethodAndStatusOrderByCreatedAtDesc(paymentMethod,
                    status);
        } else if (paymentMethod != null) {
            transactions = transactionRepository.findByPaymentMethodOrderByCreatedAtDesc(paymentMethod);
        } else if (status != null) {
            transactions = transactionRepository.findByStatusOrderByCreatedAtDesc(status);
        } else {
            transactions = transactionRepository.findAllByOrderByCreatedAtDesc();
        }
        return transactions.stream().map(PaymentResponse::fromEntity).toList();
    }

    /**
     * Solicita o estorno de uma transação elegível junto ao adaptador de liquidação legado.
     *
     * @param transactionCode código único da transação a estornar
     * @return transação atualizada com status {@link PaymentStatus#REFUNDED}
     * @throws ResourceNotFoundException quando o código não existir na base
     * @throws BusinessRuleException quando o status atual não for {@code APPROVED} nem
     *     {@code PENDING_SETTLEMENT}, ou se o mainframe recusar o estorno
     */
    @Transactional
    public PaymentResponse refundPayment(String transactionCode) {
        PaymentTransaction transaction = findTransactionOrThrow(transactionCode);

        if (transaction.getStatus() != PaymentStatus.APPROVED
                && transaction.getStatus() != PaymentStatus.PENDING_SETTLEMENT) {
            throw new BusinessRuleException(
                    "Apenas transações APPROVED ou PENDING_SETTLEMENT podem ser estornadas. Status atual: "
                            + transaction.getStatus());
        }

        SettlementResponse refundResponse = settlementPort.refund(
                transaction.getExternalAuthorizationCode(),
                transaction.getGrossAmount());

        transaction.markAsRefunded(refundResponse.authorizationCode());
        PaymentTransaction updated = transactionRepository.save(transaction);

        publishEvent(
                updated,
                EVENT_PAYMENT_REFUNDED,
                "Estornado via " + refundResponse.clearingChannel() + " reversalAuth="
                        + refundResponse.authorizationCode());

        return PaymentResponse.fromEntity(updated);
    }

    /**
     * Recupera a trilha cronológica de auditoria de uma transação existente.
     *
     * @param transactionCode código da transação
     * @return lista ordenada de eventos auditados
     * @throws ResourceNotFoundException quando a transação não existir na base
     */
    @Transactional(readOnly = true)
    public List<AuditLogResponse> getAuditTrail(String transactionCode) {
        ensureTransactionExists(transactionCode);
        return auditLogRepository.findByTransactionCodeOrderByRecordedAtAsc(transactionCode)
                .stream()
                .map(AuditLogResponse::fromEntity)
                .toList();
    }

    /**
     * Recupera todas as notificações emitidas para uma transação existente.
     *
     * @param transactionCode código da transação
     * @return lista ordenada de notificações disparadas
     * @throws ResourceNotFoundException quando a transação não existir na base
     */
    @Transactional(readOnly = true)
    public List<NotificationResponse> getNotifications(String transactionCode) {
        ensureTransactionExists(transactionCode);
        return notificationLogRepository.findByTransactionCodeOrderByDispatchedAtAsc(transactionCode)
                .stream()
                .map(NotificationResponse::fromEntity)
                .toList();
    }

    private PaymentContext buildContext(
            String transactionCode,
            String idempotencyKey,
            CreatePaymentRequest request) {
        return new PaymentContext(
                transactionCode,
                idempotencyKey,
                request.payerDocument(),
                request.payeeDocument(),
                request.amount(),
                request.paymentMethod(),
                request.resolvedCustomerTier(),
                request.resolvedInstallments(),
                request.pixKey(),
                request.description());
    }

    private PaymentTransaction findTransactionOrThrow(String transactionCode) {
        return transactionRepository.findByTransactionCode(transactionCode)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Transação de pagamento não encontrada para o código: " + transactionCode));
    }

    private void ensureTransactionExists(String transactionCode) {
        findTransactionOrThrow(transactionCode);
    }

    private void publishEvent(PaymentTransaction transaction, String eventType, String details) {
        PaymentProcessedEvent event = new PaymentProcessedEvent(
                transaction.getTransactionCode(),
                eventType,
                transaction.getPaymentMethod(),
                transaction.getStatus(),
                transaction.getGrossAmount(),
                transaction.getFeeAmount(),
                transaction.getCashbackAmount(),
                transaction.getPayerDocument(),
                transaction.getPayeeDocument(),
                transaction.getRiskLevel(),
                Instant.now(),
                details);
        eventPublisher.notifyObservers(event);
    }

    private static String normalizeBlankToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
