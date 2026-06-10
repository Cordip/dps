-- Fast smoke check for the Docker-restored demo database.

set search_path to bookings, public;

select bookings.version() as demo_version;

select exists(select 1 from flights limit 1) as has_flights;

select exists(select 1 from segments limit 1) as has_segments;
