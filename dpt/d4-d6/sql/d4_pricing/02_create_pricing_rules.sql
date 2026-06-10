-- D4: create the pricing rule table for upcoming flights.
--
-- Exact historical formula proved by 01_explore_existing_prices.sql:
--   price = f(route_no, airplane_code, fare_conditions)
--
-- If an upcoming route/airplane has a missing fare class in history, use the
-- class multipliers also proved by 01:
--   Business = Economy * 2.0
--   Comfort  = Economy * 1.3

set search_path to bookings, public;
\timing on

\echo '[D4 pricing 1/4] Create D4 pricing rule table'

drop view if exists d4_flight_prices;
drop table if exists d4_pricing_rules;

create table d4_pricing_rules (
    route_no text not null,
    airplane_code character(3) not null,
    fare_conditions text not null,
    price numeric(10,2) not null check (price > 0),
    source_rule text not null,
    source_segments integer not null check (source_segments > 0),
    distinct_source_prices integer not null check (distinct_source_prices = 1),
    created_at timestamptz not null default now(),
    primary key (route_no, airplane_code, fare_conditions)
);

comment on table d4_pricing_rules is
    'D4 pricing rules: route_no + airplane_code + fare_conditions -> price.';

comment on column d4_pricing_rules.source_rule is
    'exact_history or a class-ratio fallback derived from another fare class.';

\echo '[D4 pricing 2/4] Insert exact and inferred pricing rules (long step; wait for INSERT count and Time)'

-- Source observations: prices already stored in sold ticket segments.
with historical_segments as (
    select
        f.route_no,
        r.airplane_code,
        s.fare_conditions,
        s.price
    from bookings b
    join tickets t on t.book_ref = b.book_ref
    join segments s on s.ticket_no = t.ticket_no
    join flights f on f.flight_id = s.flight_id
    -- routes.validity selects the route version active at departure time.
    join routes r
        on r.route_no = f.route_no
       and r.validity @> f.scheduled_departure
    where b.book_date <= bookings.now()
      and s.price is not null
),
-- Exact rules are allowed only when one key has one historical price.
exact_rules as (
    select
        route_no,
        airplane_code,
        fare_conditions,
        min(price)::numeric(10,2) as price,
        count(*)::integer as source_segments,
        count(distinct price)::integer as distinct_source_prices
    from historical_segments
    group by route_no, airplane_code, fare_conditions
),
-- Generate all route/airplane/fare-class combinations that can exist.
available_rule_keys as (
    select distinct
        r.route_no,
        r.airplane_code,
        s.fare_conditions
    from routes r
    join seats s on s.airplane_code = r.airplane_code
),
-- Put exact, Economy, Comfort, and Business prices on one row for fallback.
rule_inputs as (
    select
        k.route_no,
        k.airplane_code,
        k.fare_conditions,
        exact.price as exact_price,
        exact.source_segments as exact_source_segments,
        exact.distinct_source_prices as exact_distinct_source_prices,
        economy.price as economy_price,
        economy.source_segments as economy_source_segments,
        economy.distinct_source_prices as economy_distinct_source_prices,
        comfort.price as comfort_price,
        comfort.source_segments as comfort_source_segments,
        comfort.distinct_source_prices as comfort_distinct_source_prices,
        business.price as business_price,
        business.source_segments as business_source_segments,
        business.distinct_source_prices as business_distinct_source_prices
    from available_rule_keys k
    left join exact_rules exact
        on exact.route_no = k.route_no
       and exact.airplane_code = k.airplane_code
       and exact.fare_conditions = k.fare_conditions
    left join exact_rules economy
        on economy.route_no = k.route_no
       and economy.airplane_code = k.airplane_code
       and economy.fare_conditions = 'Economy'
    left join exact_rules comfort
        on comfort.route_no = k.route_no
       and comfort.airplane_code = k.airplane_code
       and comfort.fare_conditions = 'Comfort'
    left join exact_rules business
        on business.route_no = k.route_no
       and business.airplane_code = k.airplane_code
       and business.fare_conditions = 'Business'
),
-- Prefer exact history; otherwise infer the missing class by stable ratios.
priced_rules as (
    select
        route_no,
        airplane_code,
        fare_conditions,
        case
            when exact_price is not null then exact_price
            when fare_conditions = 'Economy' and comfort_price is not null then (comfort_price / 1.3)::numeric(10,2)
            when fare_conditions = 'Economy' and business_price is not null then (business_price / 2.0)::numeric(10,2)
            when fare_conditions = 'Comfort' and economy_price is not null then (economy_price * 1.3)::numeric(10,2)
            when fare_conditions = 'Comfort' and business_price is not null then (business_price * 0.65)::numeric(10,2)
            when fare_conditions = 'Business' and economy_price is not null then (economy_price * 2.0)::numeric(10,2)
            when fare_conditions = 'Business' and comfort_price is not null then (comfort_price / 1.3 * 2.0)::numeric(10,2)
        end as price,
        case
            when exact_price is not null then 'exact_history'
            when fare_conditions = 'Economy' and comfort_price is not null then 'inferred_from_comfort_ratio_1_30'
            when fare_conditions = 'Economy' and business_price is not null then 'inferred_from_business_ratio_2_00'
            when fare_conditions = 'Comfort' and economy_price is not null then 'inferred_from_economy_ratio_1_30'
            when fare_conditions = 'Comfort' and business_price is not null then 'inferred_from_business_ratio_0_65'
            when fare_conditions = 'Business' and economy_price is not null then 'inferred_from_economy_ratio_2_00'
            when fare_conditions = 'Business' and comfort_price is not null then 'inferred_from_comfort_ratio_20_13'
        end as source_rule,
        case
            when exact_price is not null then exact_source_segments
            when fare_conditions = 'Economy' and comfort_price is not null then comfort_source_segments
            when fare_conditions = 'Economy' and business_price is not null then business_source_segments
            when fare_conditions = 'Comfort' and economy_price is not null then economy_source_segments
            when fare_conditions = 'Comfort' and business_price is not null then business_source_segments
            when fare_conditions = 'Business' and economy_price is not null then economy_source_segments
            when fare_conditions = 'Business' and comfort_price is not null then comfort_source_segments
        end as source_segments,
        case
            when exact_price is not null then exact_distinct_source_prices
            when fare_conditions = 'Economy' and comfort_price is not null then comfort_distinct_source_prices
            when fare_conditions = 'Economy' and business_price is not null then business_distinct_source_prices
            when fare_conditions = 'Comfort' and economy_price is not null then economy_distinct_source_prices
            when fare_conditions = 'Comfort' and business_price is not null then business_distinct_source_prices
            when fare_conditions = 'Business' and economy_price is not null then economy_distinct_source_prices
            when fare_conditions = 'Business' and comfort_price is not null then comfort_distinct_source_prices
        end as distinct_source_prices
    from rule_inputs
)
-- Persist only rules that were priced by history or by a proved class ratio.
insert into d4_pricing_rules (
    route_no,
    airplane_code,
    fare_conditions,
    price,
    source_rule,
    source_segments,
    distinct_source_prices
)
select
    route_no,
    airplane_code,
    fare_conditions,
    price,
    source_rule,
    source_segments,
    distinct_source_prices
from priced_rules
where price is not null;

\echo '[D4 pricing 3/4] Create restored flight price view'

create or replace view d4_flight_prices as
select
    f.flight_id,
    f.route_no,
    r.airplane_code,
    f.scheduled_departure,
    f.scheduled_arrival,
    f.status,
    pr.fare_conditions,
    pr.price,
    pr.source_rule
from flights f
join routes r
    on r.route_no = f.route_no
   and r.validity @> f.scheduled_departure
join d4_pricing_rules pr
    on pr.route_no = f.route_no
   and pr.airplane_code = r.airplane_code;

comment on view d4_flight_prices is
    'D4 restored prices for each flight and fare class, derived from d4_pricing_rules.';

\echo '[D4 pricing 4/4] Summary of created pricing rules'

select
    count(*) as pricing_rules,
    count(*) filter (where source_rule = 'exact_history') as exact_history_rules,
    count(*) filter (where source_rule <> 'exact_history') as inferred_rules,
    min(source_segments) as min_source_segments,
    max(source_segments) as max_source_segments,
    max(distinct_source_prices) as max_distinct_source_prices
from d4_pricing_rules;

\echo '[D4 pricing done] Inferred rules, expected to be few'

select *
from d4_pricing_rules
where source_rule <> 'exact_history'
order by route_no, airplane_code, fare_conditions
limit 50;
