package dev.sagaharbor.inventory.domain;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface InventoryAdjustmentRepository extends JpaRepository<InventoryAdjustment, UUID> {}
