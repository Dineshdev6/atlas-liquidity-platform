-- V2: seed reference data.
--
-- TEACHING NOTE
-- Seed data in a migration is right for genuinely static reference data -
-- currencies, country codes, the small fixed set of accounts a demo needs.
-- It is wrong for test fixtures and wrong for anything a user can change,
-- because a migration runs exactly once and can never be re-run.
--
-- In a real bank this table would be fed from a golden-source reference data
-- system, and this file would contain only the schema. We seed here so the
-- platform has something meaningful to work with from Layer 2 onward.
--
-- The set deliberately spans five residency regions so the multi-region
-- routing work in Layer 11 has something real to route.

INSERT INTO settlement_account
    (account_id, account_number, legal_entity, currency_code, jurisdiction, bic, liquidity_buffer_amount)
VALUES
    ('ACC-US-0001', '8801234567',             'ATLAS-BANK-NA',   'USD', 'US', 'ATLBUS33XXX', 25000000.0000),
    ('ACC-US-0002', '8801234568',             'ATLAS-BANK-NA',   'USD', 'US', 'FRNYUS33XXX', 40000000.0000),
    ('ACC-GB-0001', '4409876543',             'ATLAS-BANK-UK',   'GBP', 'UK', 'ATLBGB2LXXX', 15000000.0000),
    ('ACC-EU-0001', 'DE89370400440532013000', 'ATLAS-BANK-EU',   'EUR', 'EU', 'ATLBDEFFXXX', 18000000.0000),
    ('ACC-SG-0001', '6501122334',             'ATLAS-BANK-APAC', 'SGD', 'SG', 'ATLBSGSGXXX',  9000000.0000),
    -- JPY has zero minor units. Money will normalise this to a scale of 0,
    -- which is exactly the behaviour MoneyTest pins down.
    ('ACC-JP-0001', '8102233445',             'ATLAS-BANK-APAC', 'JPY', 'HK', 'ATLBHKHHXXX', 500000000.0000);
