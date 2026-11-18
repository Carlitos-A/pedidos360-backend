package com.pedidos360.pedidos.identity;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class CustomerDirectory {
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    private final ObjectMapper json;
    private final String tenantId, clientId, clientSecret, apiServicePrincipalId, customerRoleId;
    private volatile String cachedToken;
    private volatile long tokenExpiresAt;
    public record Customer(String id, String tenantId, String name, String email) {}

    public CustomerDirectory(ObjectMapper json,
            @Value("${app.identity.tenant-id}") String tenantId,
            @Value("${app.identity.graph-client-id:}") String clientId,
            @Value("${app.identity.graph-client-secret:}") String clientSecret,
            @Value("${app.identity.api-service-principal-id:}") String apiServicePrincipalId,
            @Value("${app.identity.customer-role-id:}") String customerRoleId) {
        this.json = json; this.tenantId = tenantId; this.clientId = clientId;
        this.clientSecret = clientSecret; this.apiServicePrincipalId = apiServicePrincipalId;
        this.customerRoleId = customerRoleId;
    }

    public Customer resolve(String id, String tenant) {
        if (!validUuid(id) || !tenantId.equalsIgnoreCase(tenant))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Destinatario fuera del tenant configurado");
        JsonNode user = graph("https://graph.microsoft.com/v1.0/users/" + id + "?$select=id,displayName,userPrincipalName,accountEnabled");
        if (!id.equalsIgnoreCase(user.path("id").asText()) || !user.path("accountEnabled").asBoolean(false) || !hasCustomerRole(id))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "La cuenta no tiene el rol Cliente de esta API");
        return new Customer(user.path("id").asText(), tenantId,
            user.path("displayName").asText(""), user.path("userPrincipalName").asText(""));
    }

    public List<Customer> search(String query) {
        if (query == null || query.strip().length() < 2 || query.length() > 80)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Escribe al menos dos caracteres");
        String filter = "startswith(displayName,'" + query.strip().replace("'", "''") + "')";
        String url = "https://graph.microsoft.com/v1.0/users?$select=id,displayName,userPrincipalName,accountEnabled&$top=20&$filter="
            + URLEncoder.encode(filter, StandardCharsets.UTF_8);
        List<Customer> result = new ArrayList<>();
        for (JsonNode user : graph(url).path("value")) {
            String id = user.path("id").asText();
            if (user.path("accountEnabled").asBoolean(false) && hasCustomerRole(id))
                result.add(new Customer(id, tenantId, user.path("displayName").asText(""),
                    user.path("userPrincipalName").asText("")));
        }
        return result;
    }

    private boolean hasCustomerRole(String id) {
        JsonNode data = graph("https://graph.microsoft.com/v1.0/users/" + id + "/appRoleAssignments");
        for (JsonNode assignment : data.path("value"))
            if (apiServicePrincipalId.equalsIgnoreCase(assignment.path("resourceId").asText())
                && customerRoleId.equalsIgnoreCase(assignment.path("appRoleId").asText())) return true;
        return false;
    }
    private JsonNode graph(String url) {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(5))
                .header("Authorization", "Bearer " + token()).GET().build();
            var response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 404) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Cliente no encontrado");
            if (response.statusCode() != 200) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Microsoft Graph no disponible");
            return json.readTree(response.body());
        } catch (ResponseStatusException e) { throw e; }
        catch (Exception e) { throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "No se pudo consultar Microsoft Graph", e); }
    }
    private synchronized String token() {
        if (cachedToken != null && System.currentTimeMillis() < tokenExpiresAt - 60_000) return cachedToken;
        if (clientId.isBlank() || clientSecret.isBlank() || !validUuid(apiServicePrincipalId) || !validUuid(customerRoleId))
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Directorio de clientes sin configurar");
        String body = "client_id=" + encode(clientId) + "&client_secret=" + encode(clientSecret)
            + "&scope=" + encode("https://graph.microsoft.com/.default") + "&grant_type=client_credentials";
        try {
            var request = HttpRequest.newBuilder(URI.create("https://login.microsoftonline.com/" + tenantId + "/oauth2/v2.0/token"))
                .timeout(Duration.ofSeconds(5)).header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build();
            var response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "No se pudo autenticar Microsoft Graph");
            JsonNode data = json.readTree(response.body());
            cachedToken = data.path("access_token").asText();
            tokenExpiresAt = System.currentTimeMillis() + data.path("expires_in").asLong(3600) * 1000;
            if (cachedToken.isBlank()) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Token de Graph vacío");
            return cachedToken;
        } catch (ResponseStatusException e) { throw e; }
        catch (Exception e) { throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "No se pudo autenticar Microsoft Graph", e); }
    }
    private boolean validUuid(String value) {
        if (value == null) return false;
        try { return UUID.fromString(value).toString().equalsIgnoreCase(value); }
        catch (IllegalArgumentException e) { return false; }
    }
    private String encode(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8); }
}
