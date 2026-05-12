DO $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM information_schema.tables
        WHERE table_name = 'subscription_conversation'
    ) THEN
        IF NOT EXISTS (
            SELECT 1 FROM information_schema.columns
            WHERE table_name = 'subscription_conversation'
            AND column_name = 'info_context'
        ) THEN
            ALTER TABLE subscription_conversation ADD COLUMN info_context TEXT;
        END IF;
    END IF;
END $$;
