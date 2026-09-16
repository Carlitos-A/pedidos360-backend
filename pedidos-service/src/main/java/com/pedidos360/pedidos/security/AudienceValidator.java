package com.pedidos360.pedidos.security;

import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * Validador de AUDIENCE: exige que el token haya sido emitido
 * específicamente para esta API (claim "aud").
 * La pauta lo evalúa explícitamente.
 */
public class AudienceValidator implements OAuth2TokenValidator<Jwt> {

    private final String audience;

    public AudienceValidator(String audience) {
        this.audience = audience;
    }

    @Override
    public OAuth2TokenValidatorResult validate(Jwt jwt) {
        // Entra ID puede emitir el aud como "api://<guid>" o como "<guid>" pelado.
        // Aceptamos ambas formas.
        String guidPelado = this.audience.replace("api://", "");
        if (jwt.getAudience() != null
                && (jwt.getAudience().contains(this.audience)
                    || jwt.getAudience().contains(guidPelado))) {
            return OAuth2TokenValidatorResult.success();
        }
        OAuth2Error error = new OAuth2Error(
                "invalid_token",
                "El token no fue emitido para esta API (audience inválida). Se esperaba: " + this.audience,
                null);
        return OAuth2TokenValidatorResult.failure(error);
    }
}
