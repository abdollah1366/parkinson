-- Stage 5: what a doctor prescribes for a patient (a medication at set times of day, or an exercise so many times a
-- week), and what the patient recorded doing. A change is a new prescription; the old one is stopped, so the history
-- stays as it was.
CREATE TABLE prescriptions (
    id           BIGSERIAL PRIMARY KEY,
    doctor_id    BIGINT      NOT NULL REFERENCES users (id),
    patient_id   BIGINT      NOT NULL REFERENCES patients (id) ON DELETE CASCADE,
    kind         TEXT        NOT NULL CHECK (kind IN ('MEDICATION', 'EXERCISE')),
    name         TEXT        NOT NULL,
    dose         TEXT,
    -- Times of day as HH:mm, comma separated (medications only), in the clinic's time zone.
    times        TEXT,
    per_week     INT,
    instructions TEXT,
    start_date   DATE        NOT NULL,
    end_date     DATE,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    stopped_at   TIMESTAMPTZ,
    CHECK ((kind = 'MEDICATION' AND times IS NOT NULL) OR (kind = 'EXERCISE' AND per_week IS NOT NULL))
);
CREATE INDEX prescriptions_patient ON prescriptions (patient_id, created_at DESC);

-- One dose taken or missed (scheduled_at is the dose's time), or one exercise done (scheduled_at is when).
CREATE TABLE intakes (
    id              BIGSERIAL PRIMARY KEY,
    prescription_id BIGINT      NOT NULL REFERENCES prescriptions (id) ON DELETE CASCADE,
    scheduled_at    TIMESTAMPTZ NOT NULL,
    status          TEXT        NOT NULL CHECK (status IN ('TAKEN', 'MISSED', 'DONE')),
    recorded_at     TIMESTAMPTZ NOT NULL,
    UNIQUE (prescription_id, scheduled_at)
);
