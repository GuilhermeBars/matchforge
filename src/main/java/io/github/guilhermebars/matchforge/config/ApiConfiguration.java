package io.github.guilhermebars.matchforge.config;

import com.fasterxml.jackson.databind.cfg.CoercionAction;
import com.fasterxml.jackson.databind.cfg.CoercionInputShape;
import com.fasterxml.jackson.databind.type.LogicalType;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class ApiConfiguration {
    @Bean
    OpenAPI exchangeOpenApi() {
        return new OpenAPI()
                .info(
                        new Info()
                                .title("Matchforge REST API")
                                .version("v1")
                                .description(
                                        "Deterministic exchange with exact decimal strings, account-scoped order idempotency and double-entry settlement. Authentication is out of scope. Replace quantity is remaining quantity."));
    }

    @Bean
    Jackson2ObjectMapperBuilderCustomizer strictStrings() {
        return builder -> builder.postConfigurer(mapper -> {
            mapper.coercionConfigFor(LogicalType.Textual)
                    .setCoercion(CoercionInputShape.Integer, CoercionAction.Fail)
                    .setCoercion(CoercionInputShape.Float, CoercionAction.Fail);
        });
    }
}
