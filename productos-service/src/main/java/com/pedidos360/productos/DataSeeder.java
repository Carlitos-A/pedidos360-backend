package com.pedidos360.productos;

import com.pedidos360.productos.model.Producto;
import com.pedidos360.productos.repository.ProductoRepository;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Carga datos de ejemplo al arrancar.
 */
@Configuration
public class DataSeeder {

    @Bean
    CommandLineRunner cargarDatos(ProductoRepository repository) {
        return args -> {
            if (repository.count() == 0) {
                repository.save(new Producto("Hamburguesa completa", "Sandwich", 7990.0));
                repository.save(new Producto("Pizza familiar", "Pizza", 12990.0));
                repository.save(new Producto("Completo italiano", "Sandwich", 2990.0));
                repository.save(new Producto("Bebida 500cc", "Bebestible", 1500.0));
            }
        };
    }
}
