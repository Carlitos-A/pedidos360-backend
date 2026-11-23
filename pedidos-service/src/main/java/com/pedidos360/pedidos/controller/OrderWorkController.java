package com.pedidos360.pedidos.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pedidos360.pedidos.messaging.OrderEventV2;
import com.pedidos360.pedidos.model.OrderNotification;
import com.pedidos360.pedidos.model.OrderTicket;
import com.pedidos360.pedidos.model.Pedido;
import com.pedidos360.pedidos.repository.OrderNotificationRepository;
import com.pedidos360.pedidos.repository.OrderTicketRepository;
import com.pedidos360.pedidos.repository.PedidoRepository;
import java.time.Instant;
import java.util.List;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api")
@Tag(name = "Avisos y comprobantes", description = "Trabajo opcional asíncrono de RabbitMQ")
public class OrderWorkController {
    private final PedidoRepository orders;
    private final OrderNotificationRepository notifications;
    private final OrderTicketRepository tickets;
    private final ObjectMapper mapper;
    public OrderWorkController(PedidoRepository orders, OrderNotificationRepository notifications,
                               OrderTicketRepository tickets, ObjectMapper mapper) {
        this.orders = orders; this.notifications = notifications; this.tickets = tickets; this.mapper = mapper;
    }

    public record Page<T>(List<T> items, long total, int page, int pageSize) {}
    public record NotificationView(Long id, Long orderId, String message, Instant occurredAt) {}
    public record TicketView(String ticketNumber, Long orderId, String customerName, long total,
                             Instant createdAt, Instant issuedAt, List<OrderEventV2.Line> items) {}

    @GetMapping("/notifications")
    @Operation(summary = "Lista avisos", description = "Cliente solo consulta avisos de pedidos que sigue poseyendo; Admin consulta todos")
    public Page<NotificationView> notifications(@AuthenticationPrincipal Jwt jwt,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int pageSize) {
        if (page < 0 || pageSize < 1 || pageSize > 100) bad();
        var paging = PageRequest.of(page, pageSize,
            Sort.by(Sort.Order.desc("occurredAt"), Sort.Order.desc("id")));
        org.springframework.data.domain.Page<OrderNotification> result;
        if (admin(jwt)) result = notifications.findAll(paging);
        else {
            String oid = jwt.getClaimAsString("oid"), tid = jwt.getClaimAsString("tid");
            if (blank(oid) || blank(tid)) throw new ResponseStatusException(HttpStatus.FORBIDDEN);
            result = notifications.findVisibleToCustomer(oid, tid, paging);
        }
        return new Page<>(result.getContent().stream().map(n -> new NotificationView(
            n.getId(), n.getOrderId(), n.getMessage(), n.getOccurredAt())).toList(),
            result.getTotalElements(), page, pageSize);
    }

    @GetMapping("/orders/{id}/ticket")
    @Operation(summary = "Consulta el comprobante interno de creación", description = "Devuelve 404 si aún no está generado o el Cliente no es propietario y destinatario")
    public TicketView ticket(@PathVariable Long id, @AuthenticationPrincipal Jwt jwt) {
        Pedido order = orders.findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        OrderTicket ticket = tickets.findByOrderId(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        if (!admin(jwt) && (!sameOwner(order.getCustomerId(), order.getCustomerTenantId(), jwt)
            || !sameOwner(ticket.getCustomerId(), ticket.getCustomerTenantId(), jwt)))
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        try {
            List<OrderEventV2.Line> items = mapper.readValue(ticket.getItemsJson(), new TypeReference<>() {});
            return new TicketView(ticket.getTicketNumber(), id, ticket.getCustomerName(),
                ticket.getTotalClp(), ticket.getCreatedAt(), ticket.getIssuedAt(), items);
        } catch (Exception malformed) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Ticket persistido inválido", malformed);
        }
    }

    private boolean admin(Jwt jwt) {
        return jwt.getClaimAsStringList("roles") != null && jwt.getClaimAsStringList("roles").contains("Admin");
    }
    private boolean sameOwner(String oid, String tid, Jwt jwt) {
        return !blank(oid) && !blank(tid) && oid.equals(jwt.getClaimAsString("oid"))
            && tid.equals(jwt.getClaimAsString("tid"));
    }
    private boolean blank(String value) { return value == null || value.isBlank(); }
    private void bad() { throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Paginación inválida"); }
}
