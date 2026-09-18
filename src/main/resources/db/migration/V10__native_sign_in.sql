-- M11: a native client cannot read the HttpOnly session cookie the browser flows set, so the
-- browser half of a sign-in ends by minting a one-time code instead. The app exchanges the code,
-- proving it started the flow with the verifier behind the challenge (SDD.md 6.1).
--
-- flow_hash is the hash of whichever secret drives the browser half: the magic-link token, or the
-- OAuth state. One table serves both because both end in the same place.
CREATE TABLE native_sign_in (
    flow_hash   text PRIMARY KEY,
    challenge   text NOT NULL,
    code_hash   text,
    account_id  uuid REFERENCES account (id) ON DELETE CASCADE,
    expires_at  timestamptz NOT NULL,
    consumed_at timestamptz,
    created_at  timestamptz NOT NULL
);

CREATE UNIQUE INDEX native_sign_in_code_hash_key ON native_sign_in (code_hash);
