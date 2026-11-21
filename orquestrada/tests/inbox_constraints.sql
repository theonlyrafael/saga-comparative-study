-- Run against each participant database after applying its migrations.
BEGIN;
DO $$
DECLARE
    first_message UUID := gen_random_uuid();
    first_operation UUID := gen_random_uuid();
    consumer TEXT := gen_random_uuid()::text;
BEGIN
    INSERT INTO inbox_messages (consumer_name, message_id, operation_id)
    VALUES (consumer, first_message, first_operation);

    BEGIN
        INSERT INTO inbox_messages (consumer_name, message_id, operation_id)
        VALUES (consumer, first_message, gen_random_uuid());
        RAISE EXCEPTION 'Duplicate message accepted';
    EXCEPTION WHEN unique_violation THEN NULL;
    END;

    BEGIN
        INSERT INTO inbox_messages (consumer_name, message_id, operation_id)
        VALUES (consumer, gen_random_uuid(), first_operation);
        RAISE EXCEPTION 'Repeated operation accepted with a new message ID';
    EXCEPTION WHEN unique_violation THEN NULL;
    END;

    INSERT INTO inbox_messages (consumer_name, message_id, operation_id)
    VALUES (consumer || '-other', first_message, first_operation),
           (consumer, gen_random_uuid(), gen_random_uuid());
END;
$$;
ROLLBACK;
