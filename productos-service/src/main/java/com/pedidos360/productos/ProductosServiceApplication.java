package com.pedidos360.productos;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class ProductosServiceApplication {

	public static void main(String[] args) {
		SpringApplication.run(ProductosServiceApplication.class, args);
	}

}
