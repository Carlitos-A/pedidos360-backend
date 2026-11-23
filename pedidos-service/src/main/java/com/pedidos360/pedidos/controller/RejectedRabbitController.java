package com.pedidos360.pedidos.controller;

import com.pedidos360.pedidos.model.RejectedRabbitMessage;
import com.pedidos360.pedidos.repository.RejectedRabbitMessageRepository;
import java.util.List;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/internal/rejections/rabbit")
public class RejectedRabbitController {
    public record RejectionPage<T>(List<T> items, long total, int page, int pageSize) {}
    private final RejectedRabbitMessageRepository repository;
    public RejectedRabbitController(RejectedRabbitMessageRepository repository) { this.repository = repository; }
    @GetMapping
    public RejectionPage<RejectedRabbitMessage> list(@RequestParam(defaultValue = "0") int page,
                                            @RequestParam(defaultValue = "20") int pageSize) {
        if (page < 0 || pageSize < 1 || pageSize > 100)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Paginación inválida");
        var results = repository.findAll(PageRequest.of(page, pageSize,
            Sort.by(Sort.Order.desc("observedAt"), Sort.Order.desc("id"))));
        return new RejectionPage<>(results.getContent(), results.getTotalElements(), page, pageSize);
    }
}
