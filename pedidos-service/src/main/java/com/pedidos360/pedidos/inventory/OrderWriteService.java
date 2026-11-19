package com.pedidos360.pedidos.inventory;

import com.pedidos360.pedidos.identity.CustomerDirectory.Customer;
import com.pedidos360.pedidos.messaging.OrderEventPublisher;
import com.pedidos360.pedidos.model.AuditEvent;
import com.pedidos360.pedidos.model.OrderStatus;
import com.pedidos360.pedidos.model.Pedido;
import com.pedidos360.pedidos.model.PedidoItem;
import com.pedidos360.pedidos.model.PedidoStatusEvent;
import com.pedidos360.pedidos.model.PendingOrderUpdate;
import com.pedidos360.pedidos.model.PendingInventoryRelease;
import com.pedidos360.pedidos.repository.AuditEventRepository;
import com.pedidos360.pedidos.repository.PedidoRepository;
import com.pedidos360.pedidos.repository.PendingOrderUpdateRepository;
import com.pedidos360.pedidos.repository.PendingInventoryReleaseRepository;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.ZoneId;
import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.web.server.ResponseStatusException;

@Service
public class OrderWriteService {
    private static final Logger log = LoggerFactory.getLogger(OrderWriteService.class);
    private static final ZoneId SANTIAGO = ZoneId.of("America/Santiago");
    private final PedidoRepository orders;
    private final PendingOrderUpdateRepository updates;
    private final PendingInventoryReleaseRepository releases;
    private final AuditEventRepository audit;
    private final OrderEventPublisher publisher;
    private final InventoryClient inventory;
    private final TransactionTemplate transactions;
    private final TransactionTemplate independentTransactions;

    public record Input(String requestId, Long expectedVersion, String detail, List<PedidoItem> items) {}

    public OrderWriteService(PedidoRepository orders, PendingOrderUpdateRepository updates,
                             PendingInventoryReleaseRepository releases,
                             AuditEventRepository audit, OrderEventPublisher publisher,
                             InventoryClient inventory, TransactionTemplate transactions) {
        this.orders = orders; this.updates = updates; this.releases = releases;
        this.audit = audit; this.publisher = publisher;
        this.inventory = inventory; this.transactions = transactions;
        this.independentTransactions = new TransactionTemplate(transactions.getTransactionManager());
        this.independentTransactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public Pedido create(Input input, Customer owner, String actorName, String actorEmail) {
        Pedido pending;
        try {
            pending = transactions.execute(tx -> {
            Pedido existing = orders.findByReservationKey(input.requestId()).orElse(null);
            if (existing != null) {
                verifySameRequest(existing, input, owner);
                touch(existing); return existing;
            }
            Pedido p = new Pedido();
            p.setReservationKey(input.requestId());
            p.setInventoryState("PENDING");
            p.setCustomerId(owner.id()); p.setCustomerTenantId(owner.tenantId()); p.setCliente(owner.name());
            p.setDetalle(input.detail().strip()); p.setEstado(OrderStatus.CREATED.name());
            Instant now = Instant.now();
            p.setCreatedAtUtc(now);
            p.setFecha(LocalDateTime.ofInstant(now, SANTIAGO));
            p.setRequestActorName(actorName); p.setRequestActorEmail(actorEmail);
            p.getItems().addAll(input.items());
            Pedido saved = orders.saveAndFlush(p);
            touch(saved);
            return saved;
            });
        } catch (DataIntegrityViolationException ex) {
            pending = transactions.execute(tx -> {
                Pedido found = orders.findByReservationKey(input.requestId()).orElse(null);
                if (found != null) {
                    verifySameRequest(found, input, owner);
                    touch(found);
                }
                return found;
            });
            if (pending == null)
                throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "La creación está en curso; reintenta con el mismo requestId", ex);
        }
        if (pending == null) throw new IllegalStateException("No se pudo guardar el pedido");
        if ("READY".equals(pending.getInventoryState())) return pending;
        if ("FAILED".equals(pending.getInventoryState()))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Solicitud anterior rechazada; actualiza los productos");
        return finishCreate(pending.getId());
    }

    public Pedido update(Long id, Input input, String customerId, String tenantId) {
        Pedido order = transactions.execute(tx -> {
            Pedido p = orders.findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
            if (!Objects.equals(p.getCustomerId(), customerId) || !Objects.equals(p.getCustomerTenantId(), tenantId))
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Usa la reasignación administrativa para cambiar propietario");
            if (!"READY".equals(p.getInventoryState()) && p.getInventoryState() != null)
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Pedido pendiente de inventario");
            if (!Objects.equals(p.getVersion(), input.expectedVersion()))
                throw new ResponseStatusException(HttpStatus.CONFLICT, "El pedido cambió; vuelve a cargarlo");
            if (input.items().isEmpty() && !p.getItems().isEmpty())
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "El pedido requiere al menos un producto");
            PendingOrderUpdate pending = updates.findById(id).orElse(null);
            if (pending != null) {
                if (!Objects.equals(pending.getExpectedVersion(), input.expectedVersion())
                    || !pending.getDetail().equals(input.detail().strip())
                    || !sameLines(pending.getItems(), input.items()))
                    throw new ResponseStatusException(HttpStatus.CONFLICT, "Ya hay una edición pendiente");
                touch(p);
                return p;
            }
            if (sameLines(p.getItems(), input.items())) {
                p.setDetalle(input.detail().strip());
                p.setUpdatedAt(OffsetDateTime.now(ZoneOffset.UTC));
                Pedido saved = orders.saveAndFlush(p);
                touch(saved);
                return saved;
            }
            if (OrderStatus.fromStored(p.getEstado()).map(s -> s == OrderStatus.CANCELLED || s == OrderStatus.COMPLETED).orElse(false))
                throw new ResponseStatusException(HttpStatus.CONFLICT, "No se pueden cambiar los ítems de un pedido terminal");
            updates.saveAndFlush(new PendingOrderUpdate(id, p.getVersion(), input.detail().strip(), input.items()));
            touch(p);
            return p;
        });
        if (order == null) throw new IllegalStateException("No se pudo editar el pedido");
        if (updates.existsById(id)) return finishUpdate(id);
        return order;
    }

    public boolean hasPendingUpdate(Long id) { return updates.existsById(id); }
    public void markReleasePending(Long id) { releases.save(new PendingInventoryRelease(id)); }

    private Pedido finishCreate(Long id) {
        PendingCreate p = transactions.execute(tx -> {
            Pedido found = orders.findById(id).orElseThrow();
            return new PendingCreate(found.getReservationKey(), found.getCustomerId(), found.getCustomerTenantId(),
                List.copyOf(found.getItems()));
        });
        if (p == null) throw new IllegalStateException("Pedido pendiente inexistente");
        InventoryClient.Quote quote;
        try {
            quote = inventory.reserve(p.key(), p.customerId(), p.tenantId(), p.items(), true);
        } catch (ResponseStatusException ex) {
            if (ex.getStatusCode() == HttpStatus.CONFLICT || ex.getStatusCode() == HttpStatus.BAD_REQUEST)
                transactions.executeWithoutResult(tx -> orders.findById(id).ifPresent(x -> x.setInventoryState("FAILED")));
            throw ex;
        }
        boolean[] confirmed = {false};
        Pedido ready = transactions.execute(tx -> {
            Pedido current = orders.findById(id).orElseThrow();
            if ("READY".equals(current.getInventoryState())) { touch(current); return current; }
            current.getItems().clear(); current.getItems().addAll(snapshot(quote));
            current.setTotalClp(quote.total()); current.setInventoryState("READY");
            current.getHistory().add(new PedidoStatusEvent(OrderStatus.CREATED.name(),
                OffsetDateTime.ofInstant(current.getCreatedAtUtc(), ZoneId.of("UTC")),
                current.getRequestActorName(), "Admin"));
            Pedido saved = orders.saveAndFlush(current);
            audit.save(new AuditEvent("order-created-" + id, "ORDER_CREATED", "Pedido " + id + " creado",
                current.getRequestActorEmail(), OffsetDateTime.now()));
            confirmed[0] = true;
            touch(saved);
            return saved;
        });
        if (ready == null) throw new IllegalStateException("No se pudo confirmar el pedido");
        if (confirmed[0]) {
            try { publisher.publicarPedidoCreado(ready); }
            catch (RuntimeException ex) { log.error("Pedido {} confirmado; falló la publicación RabbitMQ", id, ex); }
        }
        return ready;
    }

    private Pedido finishUpdate(Long id) {
        PendingChange pending = transactions.execute(tx -> {
            PendingOrderUpdate change = updates.findById(id).orElse(null);
            if (change == null) return null;
            Pedido order = orders.findById(id).orElseThrow();
            return new PendingChange(order.getReservationKey() == null ? "order-" + id : order.getReservationKey(),
                order.getCustomerId(), order.getCustomerTenantId(), List.copyOf(change.getItems()),
                !Objects.equals(order.getVersion(), change.getExpectedVersion()));
        });
        if (pending == null) return transactions.execute(tx -> {
            Pedido current = orders.findById(id).orElseThrow();
            touch(current);
            return current;
        });
        if (pending.stale()) {
            transactions.executeWithoutResult(tx -> updates.deleteById(id));
            throw new ResponseStatusException(HttpStatus.CONFLICT, "El pedido cambió; vuelve a cargarlo");
        }
        String key = pending.key();
        InventoryClient.Quote quote;
        try {
            quote = inventory.reserve(key, pending.customerId(), pending.tenantId(), pending.items(), true);
        } catch (ResponseStatusException ex) {
            if (ex.getStatusCode() == HttpStatus.CONFLICT || ex.getStatusCode() == HttpStatus.BAD_REQUEST)
                transactions.executeWithoutResult(tx -> updates.deleteById(id));
            throw ex;
        }
        Pedido saved = transactions.execute(tx -> {
            Pedido current = orders.findById(id).orElseThrow();
            PendingOrderUpdate change = updates.findById(id).orElse(null);
            if (change == null) { touch(current); return current; }
            if (!Objects.equals(current.getVersion(), change.getExpectedVersion()))
                throw new ResponseStatusException(HttpStatus.CONFLICT, "El pedido cambió; vuelve a cargarlo");
            current.setReservationKey(key);
            current.setDetalle(change.getDetail());
            current.getItems().clear(); current.getItems().addAll(snapshot(quote));
            current.setTotalClp(quote.total()); current.setUpdatedAt(OffsetDateTime.now(ZoneOffset.UTC));
            Pedido result = orders.saveAndFlush(current);
            updates.delete(change);
            touch(result);
            return result;
        });
        if (saved == null) throw new IllegalStateException("No se pudo guardar el pedido");
        return saved;
    }

    @Scheduled(fixedDelayString = "${app.inventory.retry-delay-ms:30000}",
               initialDelayString = "${app.inventory.initial-delay-ms:30000}")
    public void retryPending() {
        for (Pedido p : orders.findByInventoryState("PENDING")) {
            try { finishCreate(p.getId()); }
            catch (RuntimeException ex) { log.warn("Pedido {} sigue pendiente de inventario: {}", p.getId(), ex.getMessage()); }
        }
        for (PendingOrderUpdate pending : updates.findAll()) {
            try { finishUpdate(pending.getOrderId()); }
            catch (RuntimeException ex) { log.warn("Edición de pedido {} pendiente: {}", pending.getOrderId(), ex.getMessage()); }
        }
        for (PendingInventoryRelease release : releases.findAll()) {
            try { release(release.getOrderId()); }
            catch (RuntimeException ex) { log.warn("Liberación de pedido {} pendiente: {}", release.getOrderId(), ex.getMessage()); }
        }
    }

    @Scheduled(fixedDelayString = "${app.inventory.reconcile-delay-ms:300000}",
               initialDelayString = "${app.inventory.initial-delay-ms:30000}")
    public void reconcileReady() {
        for (Pedido p : orders.findByInventoryState("READY")) {
            if (p.getReservationKey() == null || "CANCELLED".equals(p.getEstado()) || updates.existsById(p.getId())) continue;
            try {
                PendingCreate snapshot = transactions.execute(tx -> {
                    Pedido current = orders.findById(p.getId()).orElseThrow();
                    return new PendingCreate(current.getReservationKey(), current.getCustomerId(),
                        current.getCustomerTenantId(), List.copyOf(current.getItems()));
                });
                if (snapshot != null)
                    inventory.reserve(snapshot.key(), snapshot.customerId(), snapshot.tenantId(), snapshot.items(), false);
            } catch (RuntimeException ex) {
                log.warn("Inventario del pedido {} necesita reconciliación: {}", p.getId(), ex.getMessage());
            }
        }
    }

    public void release(Long id) {
        PendingCreate p = independentTransactions.execute(tx -> {
            Pedido found = orders.findById(id).orElseThrow();
            if (!"CANCELLED".equals(found.getEstado()) || found.getReservationKey() == null) return null;
            return new PendingCreate(found.getReservationKey(), found.getCustomerId(), found.getCustomerTenantId(), List.of());
        });
        if (p != null) inventory.reserve(p.key(), p.customerId(), p.tenantId(), p.items(), false);
        independentTransactions.executeWithoutResult(tx -> releases.deleteById(id));
    }

    private record PendingCreate(String key, String customerId, String tenantId, List<PedidoItem> items) {}
    private record PendingChange(String key, String customerId, String tenantId, List<PedidoItem> items, boolean stale) {}

    private List<PedidoItem> snapshot(InventoryClient.Quote quote) {
        return quote.items().stream().map(x -> new PedidoItem(x.productId(), x.name(), x.quantity(), x.unitPrice())).toList();
    }
    private boolean sameLines(List<PedidoItem> a, List<PedidoItem> b) {
        if (a.size() != b.size()) return false;
        for (int i = 0; i < a.size(); i++)
            if (!Objects.equals(a.get(i).getProductId(), b.get(i).getProductId())
                || !Objects.equals(a.get(i).getQuantity(), b.get(i).getQuantity())) return false;
        return true;
    }
    private void verifySameRequest(Pedido order, Input input, Customer owner) {
        if (!Objects.equals(order.getCustomerId(), owner.id())
            || !Objects.equals(order.getCustomerTenantId(), owner.tenantId())
            || !Objects.equals(order.getDetalle(), input.detail().strip())
            || !sameLines(order.getItems(), input.items()))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Clave de solicitud usada para otro pedido");
    }
    private void touch(Pedido p) { p.getItems().size(); p.getHistory().size(); }
}
