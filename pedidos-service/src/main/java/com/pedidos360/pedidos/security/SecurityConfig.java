package com.pedidos360.pedidos.security;

import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtDecoders;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Value("${spring.security.oauth2.resourceserver.jwt.issuer-uri}")
    private String issuerUri;

    @Value("${app.security.audience}")
    private String audience;

    @Value("${app.cors.allowed-origins:http://localhost:4200}")
    private String allowedOrigins;
    @Value("${app.security.require-write-scope:false}")
    private boolean requireWriteScope;

    /**
     * Reglas de acceso:
     * - Sin token            -> 401 (Spring Security lo responde solo)
     * - Token válido sin rol -> 403
     */
    @Bean
    SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            .cors(Customizer.withDefaults())
            .csrf(csrf -> csrf.disable())
            .sessionManagement(session ->
                session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers(HttpMethod.GET, "/pedidos/**")
                    .hasAuthority("APPROLE_Admin")
                .requestMatchers(HttpMethod.GET, "/api/orders", "/api/orders/**")
                    .hasAnyAuthority("APPROLE_Admin", "APPROLE_Cliente")
                .requestMatchers(HttpMethod.GET, "/api/reports/**")
                    .hasAuthority("APPROLE_Admin")
                .requestMatchers(HttpMethod.GET, "/api/customers")
                    .hasAuthority("APPROLE_Admin")
                .requestMatchers(HttpMethod.GET, "/api/audit/events")
                    .hasAuthority("APPROLE_Admin")
                .requestMatchers(HttpMethod.GET, "/api/health")
                    .hasAuthority("APPROLE_Admin")
                .requestMatchers(HttpMethod.POST, "/api/orders", "/api/orders/**")
                    .access(AdminWriteAuthorization.require(requireWriteScope))
                .requestMatchers(HttpMethod.PUT, "/api/orders/**")
                    .access(AdminWriteAuthorization.require(requireWriteScope))
                .requestMatchers(HttpMethod.PATCH, "/api/orders/**")
                    .access(AdminWriteAuthorization.require(requireWriteScope))
                .anyRequest().denyAll())
            .oauth2ResourceServer(oauth2 -> oauth2
                .jwt(jwt -> jwt
                    .decoder(jwtDecoder())
                    .jwtAuthenticationConverter(jwtAuthenticationConverter())));
        return http.build();
    }

    /**
     * Decodificador JWT: valida FIRMA (con las claves públicas de Microsoft),
     * EMISOR (issuer) y VIGENCIA (exp) automáticamente, y le sumamos
     * nuestro validador de AUDIENCE.
     */
    @Bean
    JwtDecoder jwtDecoder() {
        NimbusJwtDecoder decoder = JwtDecoders.fromIssuerLocation(issuerUri);
        OAuth2TokenValidator<Jwt> validadores = new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefaultWithIssuer(issuerUri),
                new AudienceValidator(audience));
        decoder.setJwtValidator(validadores);
        return decoder;
    }

    /**
     * Convierte el claim "roles" del token de Entra ID
     * en autoridades de Spring con prefijo APPROLE_.
     */
    @Bean
    JwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtGrantedAuthoritiesConverter authoritiesConverter = new JwtGrantedAuthoritiesConverter();
        authoritiesConverter.setAuthoritiesClaimName("roles");
        authoritiesConverter.setAuthorityPrefix("APPROLE_");

        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(authoritiesConverter);
        return converter;
    }

    /**
     * CORS: permite que el frontend Angular (localhost:4200) llame al API.
     */
    @Bean
    CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(java.util.Arrays.stream(allowedOrigins.split(",")).map(String::strip).toList());
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("Authorization", "Content-Type"));
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }
}
