-- Stage 6: biofeedback. A doctor prescribes an exercise from the app's library (exercise code and level 1-3), so many
-- times a week; the phone sends each session's score and measurements.
ALTER TABLE prescriptions DROP CONSTRAINT prescriptions_kind_check;
ALTER TABLE prescriptions DROP CONSTRAINT prescriptions_check;
ALTER TABLE prescriptions ADD COLUMN exercise TEXT;
ALTER TABLE prescriptions ADD COLUMN level INT;
ALTER TABLE prescriptions ADD CONSTRAINT prescriptions_kind_check CHECK (kind IN ('MEDICATION', 'EXERCISE', 'BIOFEEDBACK'));
ALTER TABLE prescriptions ADD CONSTRAINT prescriptions_check CHECK (
    (kind = 'MEDICATION' AND times IS NOT NULL)
    OR (kind = 'EXERCISE' AND per_week IS NOT NULL)
    OR (kind = 'BIOFEEDBACK' AND per_week IS NOT NULL AND exercise IS NOT NULL AND level BETWEEN 1 AND 3)
);

-- One biofeedback session done on the phone. metrics: the exercise's measurements as a JSON object of numbers.
CREATE TABLE biofeedback_sessions (
    id              BIGSERIAL PRIMARY KEY,
    prescription_id BIGINT      NOT NULL REFERENCES prescriptions (id) ON DELETE CASCADE,
    started_at      TIMESTAMPTZ NOT NULL,
    duration_ms     INT         NOT NULL,
    score           INT         NOT NULL CHECK (score BETWEEN 0 AND 100),
    completed       BOOLEAN     NOT NULL,
    metrics         TEXT        NOT NULL,
    recorded_at     TIMESTAMPTZ NOT NULL,
    UNIQUE (prescription_id, started_at)
);
