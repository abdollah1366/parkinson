-- Stage 3: a patient may log in to their own panel with their national ID and a password set from the app.
ALTER TABLE patients ADD COLUMN password_hash TEXT;

-- A doctor's notes on a patient. Only notes the doctor marks as shared are shown in the patient's panel.
CREATE TABLE doctor_notes (
    id                  BIGSERIAL PRIMARY KEY,
    doctor_id           BIGINT      NOT NULL REFERENCES users (id),
    patient_id          BIGINT      NOT NULL REFERENCES patients (id) ON DELETE CASCADE,
    text                TEXT        NOT NULL,
    shared_with_patient BOOLEAN     NOT NULL DEFAULT FALSE,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX doctor_notes_patient ON doctor_notes (patient_id, created_at DESC);
