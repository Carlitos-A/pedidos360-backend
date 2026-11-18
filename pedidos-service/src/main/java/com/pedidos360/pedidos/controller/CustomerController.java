package com.pedidos360.pedidos.controller;

import com.pedidos360.pedidos.identity.CustomerDirectory;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/customers")
public class CustomerController {
    private final CustomerDirectory directory;
    public CustomerController(CustomerDirectory directory) { this.directory = directory; }
    @GetMapping
    public List<CustomerDirectory.Customer> search(@RequestParam String q) { return directory.search(q); }
}
