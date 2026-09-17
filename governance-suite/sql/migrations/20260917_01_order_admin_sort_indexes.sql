USE order_db;

ALTER TABLE t_order_admin_read_model
  ADD KEY idx_amount_cents_order_no (amount_cents, order_no),
  ADD KEY idx_payment_updated_at_order_no (payment_updated_at, order_no);
