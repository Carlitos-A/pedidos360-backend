package com.pedidos360.pedidos;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = "spring.rabbitmq.listener.simple.auto-startup=false")
class PedidosServiceApplicationTests {

	@Test
	void contextLoads() {
	}

}
