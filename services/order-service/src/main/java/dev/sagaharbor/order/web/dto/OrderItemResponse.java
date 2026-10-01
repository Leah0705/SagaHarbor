package dev.sagaharbor.order.web.dto;

public record OrderItemResponse(String sku, int quantity, MoneyDto unitPrice, MoneyDto lineTotal) {}
