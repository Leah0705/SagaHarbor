-- Operator review decisions. An order in REQUIRES_REVIEW can now be resumed or cancelled by an
-- operator (see OrderReviewService). RESUME needs to know which status to return to:
-- review_resume_status holds the status the order had when it entered review, moved forward by any
-- milestone that arrived while it waited. It is null whenever the order is not in review.
ALTER TABLE orders ADD COLUMN review_resume_status VARCHAR(30);

-- Orders already waiting for review get the last status they held before entering it.
UPDATE orders o
SET review_resume_status = (
    SELECT h.status
    FROM order_status_history h
    WHERE h.order_id = o.order_id AND h.status <> 'REQUIRES_REVIEW'
    ORDER BY h.occurred_at DESC
    LIMIT 1)
WHERE o.status = 'REQUIRES_REVIEW';

-- Reconciliation reports a stage that ran past its SLA threshold as an SLA_BREACHED incident and
-- leaves the order's status alone, instead of moving the order to REQUIRES_REVIEW.
ALTER TABLE operations_incident DROP CONSTRAINT operations_incident_kind_check;
ALTER TABLE operations_incident ADD CONSTRAINT operations_incident_kind_check
    CHECK (kind IN (
        'COMPENSATION_EXHAUSTED', 'CANCELLATION_AFTER_DISPATCH', 'CANCELLATION_STUCK',
        'SLA_BREACHED'
    ));

-- order_stage_duration keeps its UNIQUE (order_id, stage). An operator decision can make an order
-- re-enter a stage it visited before; OperationsProjectionUpdater then reopens the existing row, so
-- a stage's duration runs from its first entry to its last exit.
