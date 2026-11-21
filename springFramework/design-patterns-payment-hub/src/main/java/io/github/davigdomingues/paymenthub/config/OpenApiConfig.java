package io.github.davigdomingues.paymenthub.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Configuração da especificação OpenAPI 3 / Swagger UI do Payment Hub.
 *
 * Estrutura e impacto: define os metadados institucionais e a visão arquitetural dos 9 padrões GoF
 * exibidos em {@code /swagger-ui.html} e {@code /v3/api-docs}, sem interferir nos ciclos
 * transacionais ou de validação da API.
 */
@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI paymentHubOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("Design Patterns Payment Hub API")
                        .version("1.0.0")
                        .description("""
                                API REST modular de processamento de pagamentos construída para o Desafio de Projeto
                                da DIO (Bootcamp Itaú), demonstrando 9 padrões de projeto GoF em conjunto:
                                Singleton, Builder, Factory Method, Strategy, Chain of Responsibility,
                                Adapter, Decorator, Observer e Facade.""")
                        .contact(new Contact()
                                .name("Davi Gabriel Domingues")
                                .url("https://github.com/davigdomingues"))
                        .license(new License()
                                .name("Licença MIT")
                                .url("https://opensource.org/licenses/MIT")));
    }
}
