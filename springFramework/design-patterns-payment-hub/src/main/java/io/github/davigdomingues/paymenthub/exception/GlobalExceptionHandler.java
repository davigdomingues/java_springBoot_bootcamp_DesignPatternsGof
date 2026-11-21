package io.github.davigdomingues.paymenthub.exception;

import io.github.davigdomingues.paymenthub.dto.response.ApiErrorResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.time.Instant;
import java.util.List;

/**
 * Interceptador global ({@link RestControllerAdvice}) responsável por traduzir exceções de
 * validação, domínio, compliance e falhas internas em respostas {@link ApiErrorResponse}.
 *
 * Matriz de mapeamento HTTP e impacto arquitetural:
 * - {@code 400 Bad Request}: {@link MethodArgumentNotValidException},
 *   {@link HttpMessageNotReadableException} e {@link MethodArgumentTypeMismatchException} (erros
 *   sintáticos ou de conversão antes da camada de negócio).
 * - {@code 404 Not Found}: {@link ResourceNotFoundException}.
 * - {@code 422 Unprocessable Entity}: {@link BusinessRuleException},
 *   {@link UnsupportedPaymentMethodException} e {@link ComplianceViolationException} (preservando a
 *   lista de violações no campo {@code details}).
 * - {@code 500 Internal Server Error}: fallback genérico para {@link Exception}, registrando o
 *   stack trace completo apenas em log e devolvendo mensagem opaca.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /**
     * Converte falhas de Bean Validation ({@code @Valid}) em HTTP 400 listando cada campo inválido.
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiErrorResponse> handleValidationException(
            MethodArgumentNotValidException ex,
            HttpServletRequest request
    ) {
        List<String> details = ex.getBindingResult()
                .getFieldErrors()
                .stream()
                .map(this::formatFieldError)
                .toList();

        return buildResponse(
                HttpStatus.BAD_REQUEST,
                "Falha de validação",
                "Um ou mais campos da requisição falharam na validação",
                request.getRequestURI(),
                details
        );
    }

    /**
     * Trata JSON malformado, enums inválidos ou parâmetros de query incompatíveis com HTTP 400.
     */
    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class})
    public ResponseEntity<ApiErrorResponse> handleMalformedRequest(
            Exception ex,
            HttpServletRequest request
    ) {
        return buildResponse(
                HttpStatus.BAD_REQUEST,
                "Requisição malformada",
                "Payload da requisição ou valor de parâmetro inválido",
                request.getRequestURI(),
                List.of(extractSafeMessage(ex))
        );
    }

    /**
     * Mapeia consultas a códigos de transação inexistentes para HTTP 404.
     */
    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<ApiErrorResponse> handleResourceNotFound(
            ResourceNotFoundException ex,
            HttpServletRequest request
    ) {
        return buildResponse(
                HttpStatus.NOT_FOUND,
                "Recurso não encontrado",
                ex.getMessage(),
                request.getRequestURI(),
                List.of()
        );
    }

    /**
     * Mapeia violações de regras das estratégias, adaptador legado ou fábrica para HTTP 422.
     */
    @ExceptionHandler({BusinessRuleException.class, UnsupportedPaymentMethodException.class})
    public ResponseEntity<ApiErrorResponse> handleBusinessExceptions(
            RuntimeException ex,
            HttpServletRequest request
    ) {
        return buildResponse(
                HttpStatus.UNPROCESSABLE_ENTITY,
                "Violação de regra de negócio",
                ex.getMessage(),
                request.getRequestURI(),
                List.of()
        );
    }

    /**
     * Mapeia bloqueios da cadeia de compliance para HTTP 422 expondo o código rejeitado e flags.
     */
    @ExceptionHandler(ComplianceViolationException.class)
    public ResponseEntity<ApiErrorResponse> handleComplianceViolation(
            ComplianceViolationException ex,
            HttpServletRequest request
    ) {
        return buildResponse(
                HttpStatus.UNPROCESSABLE_ENTITY,
                "Violação de compliance",
                ex.getMessage(),
                request.getRequestURI(),
                ex.getViolations()
        );
    }

    /**
     * Fallback de segurança para exceções não previstas, evitando vazamento de stack trace ou
     * detalhes internos de infraestrutura na resposta HTTP 500.
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiErrorResponse> handleUnexpectedException(
            Exception ex,
            HttpServletRequest request
    ) {
        log.error("Erro inesperado ao processar requisição uri={}", request.getRequestURI(), ex);
        return buildResponse(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "Erro interno do servidor",
                "Ocorreu um erro interno inesperado",
                request.getRequestURI(),
                List.of()
        );
    }

    private String formatFieldError(FieldError fieldError) {
        return fieldError.getField() + ": " + fieldError.getDefaultMessage();
    }

    // Extrai a mensagem mais específica disponível sem expor stack trace ao consumidor da API
    private String extractSafeMessage(Exception ex) {
        Throwable root = ex.getCause() != null ? ex.getCause() : ex;
        return root.getMessage() != null ? root.getMessage() : "Payload da requisição inválido";
    }

    private ResponseEntity<ApiErrorResponse> buildResponse(
            HttpStatus status,
            String error,
            String message,
            String path,
            List<String> details
    ) {
        ApiErrorResponse body = new ApiErrorResponse(
                Instant.now(),
                status.value(),
                error,
                message,
                path,
                details
        );
        return ResponseEntity.status(status).body(body);
    }
}
