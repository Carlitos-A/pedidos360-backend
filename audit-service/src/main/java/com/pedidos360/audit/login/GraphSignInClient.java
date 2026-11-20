package com.pedidos360.audit.login;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = {"app.entra.signins-enabled", "app.audit.kafka-enabled"}, havingValue = "true")
public class GraphSignInClient {
    public record SignIn(String id, String userId, String email, Instant occurredAt) {}
    private final ObjectMapper mapper;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final String tenantId;
    private final String clientId;
    private final String clientSecret;
    private final String spaAppId;

    public GraphSignInClient(ObjectMapper mapper,
                             @Value("${app.entra.tenant-id:}") String tenantId,
                             @Value("${app.entra.graph-client-id:}") String clientId,
                             @Value("${app.entra.graph-client-secret:}") String clientSecret,
                             @Value("${app.entra.spa-app-id:}") String spaAppId) {
        if (tenantId.isBlank() || clientId.isBlank() || clientSecret.isBlank() || spaAppId.isBlank())
            throw new IllegalArgumentException("Falta configurar la fuente de inicios de sesión de Entra");
        UUID.fromString(tenantId); UUID.fromString(clientId); UUID.fromString(spaAppId);
        this.mapper = mapper; this.tenantId = tenantId; this.clientId = clientId;
        this.clientSecret = clientSecret; this.spaAppId = spaAppId;
    }

    public List<SignIn> list(Instant from, Instant to) throws Exception {
        String token = token();
        String filter = "createdDateTime ge " + from + " and createdDateTime le " + to
            + " and appId eq '" + spaAppId + "'";
        URI next = URI.create("https://graph.microsoft.com/v1.0/auditLogs/signIns?$top=100&$filter="
            + URLEncoder.encode(filter, StandardCharsets.UTF_8));
        List<SignIn> accepted = new ArrayList<>();
        int pages = 0;
        while (next != null) {
            if (++pages > 100) throw new IllegalStateException("Demasiadas páginas de Graph; se reintentará sin avanzar el cursor");
            if (!"https".equals(next.getScheme()) || !"graph.microsoft.com".equals(next.getHost())
                || !next.getPath().toLowerCase(Locale.ROOT).startsWith("/v1.0/auditlogs/signins"))
                throw new IllegalStateException("URL de continuación de Graph inválida");
            HttpRequest request = HttpRequest.newBuilder(next).timeout(Duration.ofSeconds(15))
                .header("Authorization", "Bearer " + token).GET().build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) throw new IllegalStateException("Graph signIns respondió " + response.statusCode());
            JsonNode body = mapper.readTree(response.body());
            if (!body.path("value").isArray()) throw new IllegalStateException("Respuesta de Graph inválida");
            for (JsonNode row : body.path("value")) {
                if (!spaAppId.equalsIgnoreCase(row.path("appId").asText())
                    || !row.path("isInteractive").asBoolean(false)
                    || row.path("status").path("errorCode").asInt(-1) != 0) continue;
                String id = row.path("id").asText();
                String userId = row.path("userId").asText();
                String email = row.path("userPrincipalName").asText();
                if (id.isBlank() || userId.isBlank() || email.isBlank()) continue;
                accepted.add(new SignIn(id, userId, email, Instant.parse(row.path("createdDateTime").asText())));
            }
            String link = body.path("@odata.nextLink").asText("");
            next = link.isBlank() ? null : URI.create(link);
        }
        return accepted;
    }

    private String token() throws Exception {
        String form = "grant_type=client_credentials&client_id=" + URLEncoder.encode(clientId, StandardCharsets.UTF_8)
            + "&client_secret=" + URLEncoder.encode(clientSecret, StandardCharsets.UTF_8)
            + "&scope=" + URLEncoder.encode("https://graph.microsoft.com/.default", StandardCharsets.UTF_8);
        HttpRequest request = HttpRequest.newBuilder(URI.create("https://login.microsoftonline.com/" + tenantId + "/oauth2/v2.0/token"))
            .timeout(Duration.ofSeconds(15)).header("Content-Type", "application/x-www-form-urlencoded")
            .POST(HttpRequest.BodyPublishers.ofString(form)).build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) throw new IllegalStateException("Token Graph respondió " + response.statusCode());
        String accessToken = mapper.readTree(response.body()).path("access_token").asText();
        if (accessToken.isBlank()) throw new IllegalStateException("Token Graph vacío");
        return accessToken;
    }
}
