-- Stage 4: text and voice messages between a doctor and a linked patient. Exactly one of body and audio is set.
-- The sound of a voice message stays in the row (at most 2 MB) and is read only when someone plays it.
CREATE TABLE messages (
    id          BIGSERIAL PRIMARY KEY,
    doctor_id   BIGINT      NOT NULL REFERENCES users (id),
    patient_id  BIGINT      NOT NULL REFERENCES patients (id) ON DELETE CASCADE,
    from_doctor BOOLEAN     NOT NULL,
    body        TEXT,
    audio       BYTEA,
    audio_type  TEXT,
    audio_ms    INT,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    read_at     TIMESTAMPTZ,
    CHECK ((body IS NULL) <> (audio IS NULL))
);
CREATE INDEX messages_conversation ON messages (doctor_id, patient_id, id);
CREATE INDEX messages_unread ON messages (doctor_id, patient_id) WHERE read_at IS NULL;
