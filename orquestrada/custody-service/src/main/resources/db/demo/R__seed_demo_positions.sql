INSERT INTO positions (position_id, owner_id, asset_id, available_quantity) VALUES
    ('00000000-0000-4000-8000-000000000001', 'COUNTERPARTY', 'ATV001', 1),
    ('00000000-0000-4000-8000-000000000002', 'COUNTERPARTY', 'ATV002', 10)
ON CONFLICT (owner_id, asset_id) DO NOTHING;
