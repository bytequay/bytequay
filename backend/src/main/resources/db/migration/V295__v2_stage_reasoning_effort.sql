ALTER TABLE stage ADD COLUMN reasoning_effort TEXT
    CHECK (reasoning_effort IS NULL OR reasoning_effort IN (
        'none', 'minimal', 'low', 'medium', 'high', 'xhigh', 'max'));
