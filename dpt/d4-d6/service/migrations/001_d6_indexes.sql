-- D6 service indexes. Existing demo indexes are kept; these cover API lookup
-- patterns that are not fully covered in the restored schema.

set search_path to bookings, public;

create index if not exists d6_flights_departure_status_idx
    on flights (scheduled_departure, status);

create index if not exists d6_routes_arrival_idx
    on routes (arrival_airport);

create index if not exists d6_tickets_book_ref_idx
    on tickets (book_ref);
