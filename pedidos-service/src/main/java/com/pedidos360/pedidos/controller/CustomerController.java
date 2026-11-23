package com.pedidos360.pedidos.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import com.pedidos360.pedidos.identity.CustomerDirectory;
import java.util.List;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/customers")
@Tag(name = "Clientes", description = "Registro local desde JWT y búsqueda administrativa")
public class CustomerController {
    private final CustomerDirectory directory;
    public CustomerController(CustomerDirectory directory) { this.directory = directory; }
    @GetMapping
    @Operation(summary = "Busca clientes registrados desde su access token")
    public List<CustomerDirectory.Customer> search(@RequestParam String q) { return directory.search(q); }
    @PostMapping("/me")
    @Operation(summary = "Registra o actualiza al Cliente autenticado sin aceptar identidad en el cuerpo")
    public CustomerDirectory.Customer register(@AuthenticationPrincipal Jwt jwt) { return directory.register(jwt); }
}
