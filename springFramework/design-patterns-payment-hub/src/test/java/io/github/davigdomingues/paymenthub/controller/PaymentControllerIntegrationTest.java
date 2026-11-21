package io.github.davigdomingues.paymenthub.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.davigdomingues.paymenthub.pattern.observer.MetricsCollectorObserver;
import io.github.davigdomingues.paymenthub.pattern.singleton.TransactionCodeGenerator;
import io.github.davigdomingues.paymenthub.repository.NotificationLogRepository;
import io.github.davigdomingues.paymenthub.repository.PaymentAuditLogRepository;
import io.github.davigdomingues.paymenthub.repository.PaymentTransactionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Testes de integração ponta a ponta do {@link PaymentController} e {@link MetricsController} com
 * contexto Spring completo, banco H2 em memória e {@link MockMvc}.
 *
 * Cobertura crítica: valida os 9 padrões GoF atuando em conjunto nas rotas REST (PIX VIP, Cartão
 * parcelado, Boleto {@code PENDING_SETTLEMENT}, idempotência, simulação sem persistência, estorno
 * com trilha atualizada, persistência após HTTP {@code 422} por
 * {@code noRollbackFor = ComplianceViolationException.class}, padronização de erros
 * {@code 400}/{@code 404} e exposição de {@code /v3/api-docs}).
 */
@SpringBootTest
@AutoConfigureMockMvc
class PaymentControllerIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private PaymentTransactionRepository transactionRepository;

    @Autowired
    private PaymentAuditLogRepository auditLogRepository;

    @Autowired
    private NotificationLogRepository notificationLogRepository;

    @Autowired
    private MetricsCollectorObserver metricsCollectorObserver;

    @BeforeEach
    void cleanState() {
        // Limpa banco H2 e estado global em memória (Singleton + Observer de métricas) entre testes
        notificationLogRepository.deleteAll();
        auditLogRepository.deleteAll();
        transactionRepository.deleteAll();
        metricsCollectorObserver.reset();
        TransactionCodeGenerator.INSTANCE.resetSequence();
    }

    @Test
    void shouldProcessPixVipCreditCardAndBoletoAndExposeAuditNotificationsAndMetrics() throws Exception {
        String pixPayload = """
                {
                  "payerDocument": "11122233344",
                  "payeeDocument": "99988877766",
                  "amount": 1000.00,
                  "paymentMethod": "PIX",
                  "customerTier": "VIP",
                  "installments": 1,
                  "pixKey": "vip.client@bank.br",
                  "idempotencyKey": "pix-vip-001",
                  "description": "Transferência PIX VIP"
                }
                """;

        MvcResult pixResult = mockMvc.perform(post("/api/v1/payments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(pixPayload))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.transactionCode").value("TXN-" + currentDatePrefix() + "-000001"))
                .andExpect(jsonPath("$.status").value("APPROVED"))
                .andExpect(jsonPath("$.feeAmount").value(0.00))
                .andExpect(jsonPath("$.cashbackAmount").value(20.00))
                .andExpect(jsonPath("$.netAmount").value(1000.00))
                .andExpect(jsonPath("$.clearingChannel").value("MAINFRAME_SPI"))
                .andExpect(jsonPath("$.appliedDecorators", hasSize(2)))
                .andReturn();

        JsonNode pixJson = objectMapper.readTree(pixResult.getResponse().getContentAsString());
        String pixCode = pixJson.get("transactionCode").asText();

        // Idempotência: segunda chamada com mesma chave retorna mesma transação
        mockMvc.perform(post("/api/v1/payments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(pixPayload))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.transactionCode").value(pixCode));

        // Cartão de Crédito 6x STANDARD (4.99% => 49.90)
        String cardPayload = """
                {
                  "payerDocument": "22233344455",
                  "payeeDocument": "99988877766",
                  "amount": 1000.00,
                  "paymentMethod": "CREDIT_CARD",
                  "customerTier": "STANDARD",
                  "installments": 6,
                  "description": "Compra parcelada 6x"
                }
                """;

        mockMvc.perform(post("/api/v1/payments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(cardPayload))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("APPROVED"))
                .andExpect(jsonPath("$.feeAmount").value(49.90))
                .andExpect(jsonPath("$.cashbackAmount").value(0.00))
                .andExpect(jsonPath("$.netAmount").value(950.10))
                .andExpect(jsonPath("$.clearingChannel").value("MAINFRAME_ACQUIRER"));

        // Boleto PREMIUM (taxa fixa 1.75, status PENDING_SETTLEMENT)
        String boletoPayload = """
                {
                  "payerDocument": "33344455566",
                  "payeeDocument": "99988877766",
                  "amount": 300.00,
                  "paymentMethod": "BOLETO",
                  "customerTier": "PREMIUM",
                  "installments": 1,
                  "description": "Boleto fornecedor"
                }
                """;

        mockMvc.perform(post("/api/v1/payments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(boletoPayload))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PENDING_SETTLEMENT"))
                .andExpect(jsonPath("$.feeAmount").value(1.75))
                .andExpect(jsonPath("$.cashbackAmount").value(3.00))
                .andExpect(jsonPath("$.clearingChannel").value("MAINFRAME_CIP"));

        // Listagem filtrada
        mockMvc.perform(get("/api/v1/payments").param("method", "BOLETO"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].paymentMethod").value("BOLETO"));

        // Trilha de auditoria e notificações do PIX
        mockMvc.perform(get("/api/v1/payments/{code}/audit", pixCode))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].eventType").value("PAYMENT_PROCESSED"));

        mockMvc.perform(get("/api/v1/payments/{code}/notifications", pixCode))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[*].channel", hasItem("PUSH")))
                .andExpect(jsonPath("$[*].channel", hasItem("EMAIL")));

        // Métricas consolidadas
        mockMvc.perform(get("/api/v1/metrics/summary"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalTransactions").value(3))
                .andExpect(jsonPath("$.approvedCount").value(2))
                .andExpect(jsonPath("$.pendingSettlementCount").value(1))
                .andExpect(jsonPath("$.totalGrossVolume").value(2300.00))
                .andExpect(jsonPath("$.totalFeesCollected").value(51.65))
                .andExpect(jsonPath("$.totalCashbackGranted").value(23.00));
    }

    @Test
    void shouldSimulateWithoutPersistingAndRefundExistingTransactionWithAuditUpdate() throws Exception {
        String simulatePayload = """
                {
                  "payerDocument": "44455566677",
                  "payeeDocument": "99988877766",
                  "amount": 500.00,
                  "paymentMethod": "PIX",
                  "customerTier": "PREMIUM",
                  "pixKey": "sim@bank.br"
                }
                """;

        mockMvc.perform(post("/api/v1/payments/simulate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(simulatePayload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.projectedStatus").value("APPROVED"))
                .andExpect(jsonPath("$.feeAmount").value(0.00))
                .andExpect(jsonPath("$.estimatedCashback").value(5.00))
                .andExpect(jsonPath("$.complianceApproved").value(true));

        assertThat(transactionRepository.count()).isZero();

        MvcResult created = mockMvc.perform(post("/api/v1/payments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(simulatePayload))
                .andExpect(status().isCreated())
                .andReturn();

        String code = objectMapper.readTree(created.getResponse().getContentAsString())
                .get("transactionCode").asText();

        mockMvc.perform(post("/api/v1/payments/{code}/refund", code))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REFUNDED"));

        mockMvc.perform(get("/api/v1/payments/{code}/audit", code))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[1].eventType").value("PAYMENT_REFUNDED"));

        mockMvc.perform(get("/api/v1/payments/{code}/notifications", code))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(4)));
    }

    @Test
    void shouldPersistRejectedTransactionAndFraudAlertWhenBlockedByCompliance() throws Exception {
        String sanctionedPayload = """
                {
                  "payerDocument": "00000000000",
                  "payeeDocument": "99988877766",
                  "amount": 400.00,
                  "paymentMethod": "PIX",
                  "pixKey": "fraud@bank.br"
                }
                """;

        // Comprova efeito prático de noRollbackFor: HTTP 422 é devolvido, mas transação e alerta persistem no H2
        MvcResult errorResult = mockMvc.perform(post("/api/v1/payments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(sanctionedPayload))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("Violação de compliance"))
                .andExpect(jsonPath("$.details[0]").value("SANCTIONED_DOCUMENT"))
                .andReturn();

        String message = objectMapper.readTree(errorResult.getResponse().getContentAsString())
                .get("message").asText();
        String rejectedCode = message.substring(message.indexOf("TXN-"));

        mockMvc.perform(get("/api/v1/payments/{code}", rejectedCode))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REJECTED"))
                .andExpect(jsonPath("$.riskLevel").value("BLOCKED"));

        mockMvc.perform(get("/api/v1/payments/{code}/notifications", rejectedCode))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].channel", hasItem("FRAUD_ALERT_WEBHOOK")));
    }

    @Test
    void shouldReturnStandardized400And404AndExposeOpenApiDocs() throws Exception {
        String invalidPayload = """
                {
                  "payerDocument": "123",
                  "payeeDocument": "",
                  "amount": 0.00,
                  "paymentMethod": "PIX"
                }
                """;

        mockMvc.perform(post("/api/v1/payments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(invalidPayload))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Falha de validação"))
                .andExpect(jsonPath("$.details").isArray());

        mockMvc.perform(get("/api/v1/payments/TXN-NONEXISTENT-999999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("Recurso não encontrado"));

        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk());
    }

    private String currentDatePrefix() {
        return java.time.LocalDate.now(java.time.ZoneOffset.UTC)
                .format(java.time.format.DateTimeFormatter.BASIC_ISO_DATE);
    }
}
