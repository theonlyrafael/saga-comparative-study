INSERT INTO accounts (account_id, available_balance) VALUES
    ('CLI001', 1000.00),
    ('CLI002',   10.00)
ON CONFLICT (account_id) DO NOTHING;
