-- Appoints users as administrators of a customer (고객사). Combined with ROLE_CUSTOMER_ADMIN it scopes them
-- to administer every organization (branch) under that customer. GLOBAL (not org-scoped, no RLS) — the
-- customer registry is global. A user may administer several customers; a (customer, user) pair is unique.
CREATE TABLE customer_membership (
    id           uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    created_at   timestamptz NOT NULL DEFAULT now(),
    customer_id  uuid NOT NULL REFERENCES customer (id) ON DELETE CASCADE,
    user_id      uuid NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    UNIQUE (customer_id, user_id)
);

CREATE INDEX idx_customer_membership_user ON customer_membership (user_id);
