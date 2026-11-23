package com.pedidos360.kafkaadmin.openapi;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {
    @Bean
    OpenAPI serviceOpenApi() {
        Components components = new Components().addSecuritySchemes("bearerAuth",
            new SecurityScheme().type(SecurityScheme.Type.HTTP).scheme("bearer").bearerFormat("JWT"));
        SecurityRequirement required = new SecurityRequirement().addList("bearerAuth");
        components.addSecuritySchemes("internalKey", new SecurityScheme()
            .type(SecurityScheme.Type.APIKEY).in(SecurityScheme.In.HEADER).name("X-Internal-Key"));
        required.addList("internalKey");
        return new OpenAPI().info(new Info().title("Pedidos360 Kafka Admin").version("1.0")
            .description("Administración Kafka en red interna. Requiere Admin y X-Internal-Key.")).components(components).addSecurityItem(required);
    }

    @Bean
    OpenApiCustomizer standardResponses() {
        return api -> {
            if (api.getPaths() == null) return;
            api.getPaths().forEach((path, item) -> item.readOperationsMap().forEach((method, operation) -> {
                ApiResponses responses = operation.getResponses();
                if (responses == null) { responses = new ApiResponses(); operation.setResponses(responses); }
                responses.putIfAbsent("401", new ApiResponse().description("Bearer ausente, expirado o inválido"));
                responses.putIfAbsent("403", new ApiResponse().description("Rol, scope o clave interna insuficiente"));
                if (path.equals("/internal/kafka/topics")
                    && method == io.swagger.v3.oas.models.PathItem.HttpMethod.POST)
                    responses.putIfAbsent("409", new ApiResponse().description("Tópico ya existente"));
            }));
        };
    }
}
