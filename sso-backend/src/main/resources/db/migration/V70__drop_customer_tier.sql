-- Finish collapsing the customer (고객사) tier: the organization is the sole tenant and the user-identity
-- boundary (org_id). Drop every customer relationship and the registry itself. organization.customer_id is no
-- longer in any constraint (V66 replaced UNIQUE(customer_id, slug) with UNIQUE(slug)); its FK/index drop with
-- the column, as do app_user.customer_id's (V65). customer_membership and customer are then unreferenced.
ALTER TABLE organization DROP COLUMN customer_id;
ALTER TABLE app_user DROP COLUMN customer_id;
DROP TABLE customer_membership;
DROP TABLE customer;
