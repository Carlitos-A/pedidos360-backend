package com.pedidos360.pedidos.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import com.pedidos360.pedidos.identity.CustomerDirectory;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/customers")
@Tag(name = "Clientes", description = "Búsqueda administrativa en Microsoft Entra")
public class CustomerController {
    private final CustomerDirectory directory;
    public CustomerController(CustomerDirectory directory) { this.directory = directory; }
    @GetMapping
    @Operation(summary = "Busca clientes verificables para asignar pedidos")
    public List<CustomerDirectory.Customer> search(@RequestParam String q) { return directory.search(q); }
}
