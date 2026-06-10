set search_path to bookings, public;

create table if not exists d6_idempotency_keys (
    key text primary key,
    request_hash text not null,
    status text not null check (status in ('in_progress', 'completed')),
    response_status integer,
    response_body jsonb,
    created_at timestamp with time zone not null default bookings.now(),
    updated_at timestamp with time zone not null default bookings.now()
);

create index if not exists d6_idempotency_keys_created_at_idx
    on d6_idempotency_keys (created_at);
