-- D4: validate that upcoming flight fare options can be priced.
--
-- Run after 02_create_pricing_rules.sql.

set search_path to bookings, public;

\echo 'Validate future fare option coverage'

-- Future fare options are flight/class pairs that a new booking may need.
with upcoming_fare_options as (
    select distinct
        f.flight_id,
        f.route_no,
        r.airplane_code,
        f.scheduled_departure,
        f.status,
        s.fare_conditions
    from flights f
    -- Use the same temporal route lookup as in the pricing table.
    join routes r
        on r.route_no = f.route_no
       and r.validity @> f.scheduled_departure
    join seats s on s.airplane_code = r.airplane_code
    where f.scheduled_departure >= bookings.now()
)
select
    count(*) as upcoming_fare_options,
    count(fp.price) as priced_options,
    count(*) - count(fp.price) as missing_options
from upcoming_fare_options u
left join d4_flight_prices fp
    on fp.flight_id = u.flight_id
   and fp.fare_conditions = u.fare_conditions;

\echo 'Missing future fare options, expected: 0 rows'

-- Missing rows should be empty.
-- If this returns rows, D4 pricing is incomplete for future booking flow.
with upcoming_fare_options as (
    select distinct
        f.flight_id,
        f.route_no,
        r.airplane_code,
        f.scheduled_departure,
        f.status,
        s.fare_conditions
    from flights f
    join routes r
        on r.route_no = f.route_no
       and r.validity @> f.scheduled_departure
    join seats s on s.airplane_code = r.airplane_code
    where f.scheduled_departure >= bookings.now()
)
select
    u.flight_id,
    u.route_no,
    u.airplane_code,
    u.scheduled_departure,
    u.status,
    u.fare_conditions
from upcoming_fare_options u
left join d4_flight_prices fp
    on fp.flight_id = u.flight_id
   and fp.fare_conditions = u.fare_conditions
where fp.flight_id is null
order by u.scheduled_departure, u.route_no, u.airplane_code, u.fare_conditions
limit 50;

\echo 'Sample priced future fare options'

-- Sample priced future fare options.
-- Use this output during defense to show how a future flight gets its price.
select distinct
    flight_id,
    route_no,
    airplane_code,
    scheduled_departure,
    status,
    fare_conditions,
    price,
    source_rule
from d4_flight_prices
where scheduled_departure >= bookings.now()
order by scheduled_departure, route_no, airplane_code, fare_conditions
limit 50;
