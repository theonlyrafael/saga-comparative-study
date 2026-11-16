INSERT INTO asset_catalog VALUES ('ATV001', 'demo-v1', 10.00), ('ATV002', 'demo-v1', 25.50) ON CONFLICT (asset_id) DO NOTHING;
