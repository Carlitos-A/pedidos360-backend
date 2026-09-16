package com.pedidos360.pedidos;

import com.pedidos360.pedidos.model.Pedido;
import com.pedidos360.pedidos.repository.PedidoRepository;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Carga datos de ejemplo al arrancar, para que GET /pedidos
 * devuelva contenido sin tener que insertar nada a mano.
 */
@Configuration
public class DataSeeder {

    @Bean
    CommandLineRunner cargarDatos(PedidoRepository repository) {
        return args -> {
            if (repository.count() == 0) {
                repository.save(new Pedido("Cliente Demo", "2x Hamburguesa completa", 15990.0, "PENDIENTE"));
                repository.save(new Pedido("Cliente Demo", "1x Pizza familiar", 12990.0, "ENTREGADO"));
                repository.save(new Pedido("Cliente Demo", "3x Completo italiano", 8970.0, "EN_PREPARACION"));
            }
        };
    }
}
