package io.github.davigdomingues.paymenthub.pattern.adapter;

import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Padrão GoF: Adapter (Adaptee - Cliente de Mainframe Legado)
 *
 * Estrutura e ponto de maior impacto: simula um barramento bancário legado com interface
 * incompatível com o domínio moderno: opera exclusivamente por meio de strings posicionais
 * delimitadas por pipe ({@code |}) e quantias inteiras em centavos. Devolve strings no padrão
 * {@code OK|AUTH/REV-...|CANAL} ou {@code ERR|CODIGO|MENSAGEM}, exigindo tradução e proteção contra
 * injeção de delimitadores pelo {@link LegacyMainframeSettlementAdapter}.
 */
@Component
public class LegacyMainframeClient {

    /**
     * Envia um comando bruto no formato posicional legado e devolve a resposta bruta.
     *
     * Formatos aceitos:
     * - {@code SETTLE|transactionCode|amountInCents|paymentMethod|payer|payee}
     * - {@code REFUND|authorizationCode|amountInCents}
     *
     * @param rawPipePayload payload delimitado por pipe
     * @return resposta no formato {@code OK|AUTH-...|CHANNEL} ou {@code ERR|CODE|MESSAGE}
     */
    public String transmitPipeFrame(String rawPipePayload) {
        if (rawPipePayload == null || rawPipePayload.isBlank()) {
            return "ERR|EMPTY_FRAME|O payload não pode estar em branco";
        }

        String[] fields = rawPipePayload.split("\\|");
        String operation = fields[0];

        if ("SETTLE".equals(operation) && fields.length == 6) {
            long cents = parsePositiveCents(fields[2]);
            if (cents <= 0) {
                return "ERR|INVALID_AMOUNT|O valor em centavos deve ser positivo";
            }
            String authSuffix = shortDigest(rawPipePayload);
            String channel = resolveLegacyChannel(fields[3]);
            return "OK|AUTH-" + authSuffix + "|" + channel;
        }

        if ("REFUND".equals(operation) && fields.length == 3) {
            if (fields[1].isBlank()) {
                return "ERR|MISSING_AUTH|O código de autorização é obrigatório para estorno";
            }
            long cents = parsePositiveCents(fields[2]);
            if (cents <= 0) {
                return "ERR|INVALID_AMOUNT|O valor de estorno em centavos deve ser positivo";
            }
            String revSuffix = shortDigest(rawPipePayload);
            return "OK|REV-" + revSuffix + "|MAINFRAME_REVERSAL";
        }

        return "ERR|MALFORMED_FRAME|Comando não suportado pelo mainframe legado";
    }

    private long parsePositiveCents(String rawCents) {
        try {
            return Long.parseLong(rawCents);
        } catch (NumberFormatException ex) {
            return -1L;
        }
    }

    private String resolveLegacyChannel(String paymentMethod) {
        return switch (paymentMethod) {
            case "PIX" -> "MAINFRAME_SPI";
            case "CREDIT_CARD" -> "MAINFRAME_ACQUIRER";
            case "BOLETO" -> "MAINFRAME_CIP";
            default -> "MAINFRAME_CORE";
        };
    }

    private String shortDigest(String seed) {
        return UUID.nameUUIDFromBytes(seed.getBytes(StandardCharsets.UTF_8))
                .toString()
                .replace("-", "")
                .substring(0, 10)
                .toUpperCase();
    }
}
