package com.pedidos360.pedidos.repository;

import com.pedidos360.pedidos.model.RegisteredCustomer;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RegisteredCustomerRepository extends JpaRepository<RegisteredCustomer, RegisteredCustomer.Key> {
    @Query("select c from RegisteredCustomer c where c.id.customerTenantId = :tenant "
        + "and (lower(c.name) like lower(concat('%', :query, '%')) "
        + "or lower(c.email) like lower(concat('%', :query, '%'))) order by c.name, c.id.customerId")
    List<RegisteredCustomer> search(@Param("tenant") String tenant, @Param("query") String query, Pageable page);
}
