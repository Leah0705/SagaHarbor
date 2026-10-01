-- One row per order whose OrderCancellationRequested.v1 this service has processed. Order Service
-- may cancel an order before this service starts work on it, so an InventoryReserved.v1 that arrives afterwards must not start an authorization. See OrderCancellationGuard.
CREATE TABLE order_cancellation_marker (
    order_id       UUID PRIMARY KEY,
    correlation_id UUID,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);
