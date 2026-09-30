package dev.sagaharbor.inventory.service;

public class ProductNotFoundException extends RuntimeException {

  public ProductNotFoundException(String sku) {
    super("no product exists for sku " + sku);
  }
}
