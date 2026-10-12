-- Staff accounts. Patients do not log in to the server yet (their panel is a later stage); their phone uses a
-- device token instead.
CREATE TABLE users (
    id                 BIGSERIAL PRIMARY KEY,
    role               TEXT        NOT NULL CHECK (role IN ('ADMIN', 'DOCTOR')),
    full_name          TEXT        NOT NULL,
    mobile             TEXT        NOT NULL UNIQUE,
    password_hash      TEXT        NOT NULL,
    medical_council_no TEXT,
    active             BOOLEAN     NOT NULL DEFAULT TRUE,
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- A person who sent results from the app. The national ID is never stored in clear: only a keyed hash (to find
-- the same person again) and its last 4 digits (to show it masked, as the app does).
CREATE TABLE patients (
    id                  BIGSERIAL PRIMARY KEY,
    national_id_hash    TEXT        NOT NULL UNIQUE,
    national_id_last4   TEXT        NOT NULL,
    first_name          TEXT        NOT NULL,
    last_name           TEXT        NOT NULL,
    age                 INT         NOT NULL,
    gender              TEXT        NOT NULL,
    mobile              TEXT        NOT NULL,
    parkinson_diagnosed BOOLEAN     NOT NULL,
    takes_medication    BOOLEAN,
    dominant_hand       TEXT        NOT NULL,
    consent_at          TIMESTAMPTZ NOT NULL,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Which doctor may see which patient. A patient joins a doctor only by entering that doctor's invite code.
CREATE TABLE doctor_patients (
    doctor_id  BIGINT      NOT NULL REFERENCES users (id),
    patient_id BIGINT      NOT NULL REFERENCES patients (id) ON DELETE CASCADE,
    linked_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (doctor_id, patient_id)
);
CREATE INDEX doctor_patients_patient ON doctor_patients (patient_id);

-- Single-use codes a doctor gives a patient to connect the app.
CREATE TABLE invites (
    code       TEXT PRIMARY KEY,
    doctor_id  BIGINT      NOT NULL REFERENCES users (id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at TIMESTAMPTZ NOT NULL,
    used_at    TIMESTAMPTZ,
    patient_id BIGINT REFERENCES patients (id) ON DELETE SET NULL
);
CREATE INDEX invites_doctor ON invites (doctor_id, created_at DESC);

-- Upload credentials of one patient on one phone. Only the SHA-256 of the token is kept.
CREATE TABLE device_tokens (
    id           BIGSERIAL PRIMARY KEY,
    patient_id   BIGINT      NOT NULL REFERENCES patients (id) ON DELETE CASCADE,
    token_hash   TEXT        NOT NULL UNIQUE,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_seen_at TIMESTAMPTZ,
    revoked_at   TIMESTAMPTZ
);

-- One finished test as the app saved it. assessment_id is the app's own id, so sending the same result twice
-- stores it once.
CREATE TABLE results (
    id                BIGSERIAL PRIMARY KEY,
    patient_id        BIGINT      NOT NULL REFERENCES patients (id) ON DELETE CASCADE,
    assessment_id     TEXT        NOT NULL,
    type              TEXT        NOT NULL,
    taken_at          TIMESTAMPTZ NOT NULL,
    hand              TEXT,
    quality_status    TEXT        NOT NULL,
    quality_score     INT,
    performance_index INT,
    medication_state  TEXT,
    algorithm_version TEXT        NOT NULL,
    scoring_version   TEXT        NOT NULL,
    metrics           JSONB       NOT NULL,
    received_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (patient_id, assessment_id)
);
CREATE INDEX results_patient_time ON results (patient_id, taken_at DESC);

-- Every time staff open a patient's record.
CREATE TABLE access_log (
    id         BIGSERIAL PRIMARY KEY,
    user_id    BIGINT      NOT NULL REFERENCES users (id),
    patient_id BIGINT REFERENCES patients (id) ON DELETE SET NULL,
    action     TEXT        NOT NULL,
    at         TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX access_log_patient ON access_log (patient_id, at DESC);
