package com.pedidos360.pedidos.messaging;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pedidos360.pedidos.model.OrderNotification;
import com.pedidos360.pedidos.model.OrderTicket;
import com.pedidos360.pedidos.repository.OrderNotificationRepository;
import com.pedidos360.pedidos.repository.OrderTicketRepository;
import java.time.Instant;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

@Service
public class OrderWorkService {
    private final OrderNotificationRepository notifications;
    private final OrderTicketRepository tickets;
    private final ObjectMapper mapper;
    public OrderWorkService(OrderNotificationRepository notifications, OrderTicketRepository tickets,
                            ObjectMapper mapper) {
        this.notifications = notifications; this.tickets = tickets; this.mapper = mapper;
    }

    public void notifyCustomer(OrderWorkEvent event) {
        if (notifications.existsByEventId(event.eventId())) return;
        try {
            notifications.saveAndFlush(new OrderNotification(event.eventId(), event.orderId(),
                event.customerId(), event.customerTenantId(), "Tu pedido #" + event.orderId() + " fue creado",
                event.occurredAt()));
        } catch (DataIntegrityViolationException duplicate) {
            if (!notifications.existsByEventId(event.eventId())) throw duplicate;
        }
    }

    public void issueTicket(OrderWorkEvent event) {
        if (tickets.existsByEventId(event.eventId()) || tickets.existsByOrderId(event.orderId())) return;
        String items;
        try { items = mapper.writeValueAsString(event.items()); }
        catch (JsonProcessingException ex) { throw new IllegalStateException("No se pudo serializar ticket", ex); }
        try {
            tickets.saveAndFlush(new OrderTicket(event.eventId(), event.orderId(),
                "P360-" + event.orderId(), event.customerName(), event.customerId(), event.customerTenantId(),
                event.total(), event.createdAt(), Instant.now(), items));
        } catch (DataIntegrityViolationException duplicate) {
            if (!tickets.existsByEventId(event.eventId()) && !tickets.existsByOrderId(event.orderId())) throw duplicate;
        }
    }
}
