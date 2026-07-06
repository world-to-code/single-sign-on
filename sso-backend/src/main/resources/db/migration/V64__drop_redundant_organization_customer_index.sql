-- V63's UNIQUE(customer_id, slug) index leads with customer_id, so it already serves every customer_id-prefix
-- query (findByCustomerIdAndSlug, existsByIdAndCustomerIdIn, findIdsByCustomerIdIn). The standalone FK index
-- from V61 is now redundant write-amplification — drop it.
DROP INDEX idx_organization_customer;
