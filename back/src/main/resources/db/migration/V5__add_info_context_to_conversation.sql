ALTER TABLE subscription_conversation
    ADD COLUMN IF NOT EXISTS info_context TEXT;
