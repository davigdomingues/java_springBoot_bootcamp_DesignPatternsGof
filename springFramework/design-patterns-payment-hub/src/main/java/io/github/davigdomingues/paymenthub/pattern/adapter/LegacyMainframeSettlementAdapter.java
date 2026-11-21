package io.github.davigdomingues.paymenthub.pattern.adapter;

import io.github.davigdomingues.paymenthub.exception.BusinessRuleException;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

/**
 * Padrão GoF: Adapter (Implementação Concreta / Fronteira Anticorrupção)
 *
 * Estrutura e ponto de maior impacto: traduz o contrato tipado {@link ExternalSettlementPort} para
 * o protocolo posicional de {@link LegacyMainframeClient}. Seus três mecanismos críticos são: (1)
 * conversão exata {@code BigDecimal -> centavos} via {@link BigDecimal#longValueExact()} para
 * evitar arredondamento silencioso ou overflow; (2) sanitização de caracteres {@code |} nos campos
 * textuais para prevenir corrupção posicional do frame legado; e (3) conversão de respostas
 * {@code ERR|...} ou vazias em {@link BusinessRuleException}.
 */
@Component
public class LegacyMainframeSettlementAdapter implements ExternalSettlementPort {

    private static final BigDecimal ONE_HUNDRED = new BigDecimal("100");

    private final LegacyMainframeClient mainframeClient;

    public LegacyMainframeSettlementAdapter(LegacyMainframeClient mainframeClient) {
        this.mainframeClient = Objects.requireNonNull(mainframeClient, "mainframeClient não pode ser nulo");
    }

    /**
     * Traduz a requisição moderna em frame {@code SETTLE|...} com valor em centavos.
     *
     * @throws NullPointerException quando {@code request} for nulo
     * @throws ArithmeticException se o valor em centavos exceder {@code Long.MAX_VALUE}
     * @throws BusinessRuleException se o mainframe responder {@code ERR|...} ou resposta vazia
     */
    @Override
    public SettlementResponse settle(SettlementRequest request) {
        Objects.requireNonNull(request, "request não pode ser nulo");
        long amountInCents = toCents(request.amount());

        String frame = String.join(
                "|",
                "SETTLE",
                sanitize(request.transactionCode()),
                Long.toString(amountInCents),
                sanitize(request.paymentMethod().name()),
                sanitize(request.payerDocument()),
                sanitize(request.payeeDocument())
        );

        String rawResponse = mainframeClient.transmitPipeFrame(frame);
        return parseProtocolResponse(rawResponse);
    }

    /**
     * Traduz o estorno para frame {@code REFUND|authCode|centavos}.
     *
     * @throws NullPointerException quando {@code amount} for nulo
     * @throws BusinessRuleException quando {@code externalAuthorizationCode} estiver vazio ou o
     *     mainframe rejeitar o frame
     */
    @Override
    public SettlementResponse refund(String externalAuthorizationCode, BigDecimal amount) {
        if (externalAuthorizationCode == null || externalAuthorizationCode.isBlank()) {
            throw new BusinessRuleException("O código de autorização externo é obrigatório para estorno");
        }
        Objects.requireNonNull(amount, "amount não pode ser nulo");
        long amountInCents = toCents(amount);

        String frame = String.join(
                "|",
                "REFUND",
                sanitize(externalAuthorizationCode),
                Long.toString(amountInCents)
        );

        String rawResponse = mainframeClient.transmitPipeFrame(frame);
        return parseProtocolResponse(rawResponse);
    }

    private long toCents(BigDecimal amount) {
        // longValueExact falha explicitamente em caso de overflow ou fração residual inesperada
        return amount.setScale(2, RoundingMode.HALF_EVEN)
                .multiply(ONE_HUNDRED)
                .longValueExact();
    }

    private String sanitize(String field) {
        // Substitui pipes para impedir deslocamento posicional de colunas no parser legado
        return field == null ? "" : field.replace("|", "_").trim();
    }

    private SettlementResponse parseProtocolResponse(String rawResponse) {
        if (rawResponse == null || rawResponse.isBlank()) {
            throw new BusinessRuleException("Resposta vazia recebida do mainframe legado");
        }

        String[] parts = rawResponse.split("\\|", 3);
        String statusToken = parts[0];
        if ("OK".equals(statusToken) && parts.length == 3) {
            return new SettlementResponse(true, parts[1], parts[2], rawResponse);
        }

        String errorCode = parts.length >= 2 ? parts[1] : "UNKNOWN_ERROR";
        String errorMessage = parts.length == 3 ? parts[2] : rawResponse;
        throw new BusinessRuleException("Falha na liquidação no mainframe legado [" + errorCode + "]: " + errorMessage);
    }
}
