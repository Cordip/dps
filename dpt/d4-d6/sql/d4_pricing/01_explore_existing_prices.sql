-- D4: inspect historical prices in the 2025 PostgresPro demo database.
--
-- Purpose:
-- - prove which columns determine the historical segment price;
-- - keep output short enough to discuss during defense;
-- - avoid rescanning the large source tables for every diagnostic query.
--
-- Run:
--   ./scripts/run_sql_docker.sh sql/d4_pricing/01_explore_existing_prices.sql

\timing on

set search_path to bookings, public;

\echo 'Build one temporary historical segment dataset'

drop table if exists d4_historical_segments;

create temp table d4_historical_segments as
select
    f.route_no,
    r.airplane_code,
    s.fare_conditions,
    s.price
from bookings b
join tickets t on t.book_ref = b.book_ref
join segments s on s.ticket_no = t.ticket_no
join flights f on f.flight_id = s.flight_id
-- Routes are temporal: use the route version that was active at departure.
join routes r
    on r.route_no = f.route_no
   and r.validity @> f.scheduled_departure
where b.book_date <= bookings.now()
  and s.price is not null;

analyze d4_historical_segments;

\echo 'Build reusable grouped statistics'

drop table if exists d4_route_class_stats;
drop table if exists d4_route_class_airplane_stats;

create temp table d4_route_class_stats as
select
    route_no,
    fare_conditions,
    count(*) as segment_count,
    count(distinct price) as distinct_prices,
    count(distinct airplane_code) as airplane_variants,
    min(price) as min_price,
    percentile_disc(0.5) within group (order by price) as median_price,
    max(price) as max_price
from d4_historical_segments
group by route_no, fare_conditions;

create temp table d4_route_class_airplane_stats as
select
    route_no,
    airplane_code,
    fare_conditions,
    count(*) as segment_count,
    count(distinct price) as distinct_prices,
    min(price) as price
from d4_historical_segments
group by route_no, airplane_code, fare_conditions;

analyze d4_route_class_stats;
analyze d4_route_class_airplane_stats;

-- This proves why airplane_code must be part of the price key.
\echo 'Summary: route+class is ambiguous, route+airplane+class is exact'

select 'historical_segments' as metric, count(*)::text as value
from d4_historical_segments
union all
select 'route_class_groups', count(*)::text
from d4_route_class_stats
union all
select 'route_class_groups_with_multiple_prices', count(*)::text
from d4_route_class_stats
where distinct_prices > 1
union all
select 'max_prices_per_route_class', max(distinct_prices)::text
from d4_route_class_stats
union all
select 'route_airplane_class_groups', count(*)::text
from d4_route_class_airplane_stats
union all
select 'route_airplane_class_groups_with_multiple_prices', count(*)::text
from d4_route_class_airplane_stats
where distinct_prices > 1
union all
select 'max_prices_per_route_airplane_class', max(distinct_prices)::text
from d4_route_class_airplane_stats;

\echo 'Top route+class groups where price is not uniquely determined'

select
    route_no,
    fare_conditions,
    segment_count,
    distinct_prices,
    airplane_variants,
    min_price,
    median_price,
    max_price
from d4_route_class_stats
where distinct_prices > 1
order by distinct_prices desc, segment_count desc, route_no, fare_conditions
limit 20;

\echo 'Examples showing that airplane_code explains the price difference'

select
    rca.route_no,
    rca.fare_conditions,
    string_agg(
        format('%s=%s (%s segments)', rca.airplane_code, rca.price, rca.segment_count),
        ', '
        order by rca.airplane_code
    ) as airplane_prices
from d4_route_class_airplane_stats rca
join d4_route_class_stats rc
    on rc.route_no = rca.route_no
   and rc.fare_conditions = rca.fare_conditions
where rc.distinct_prices > 1
group by rca.route_no, rca.fare_conditions, rc.distinct_prices, rc.segment_count
order by rc.distinct_prices desc, rc.segment_count desc, rca.route_no, rca.fare_conditions
limit 20;

\echo 'Counterexamples to route+airplane+class formula, expected: 0 rows'

select
    route_no,
    airplane_code,
    fare_conditions,
    segment_count,
    distinct_prices,
    price
from d4_route_class_airplane_stats
where distinct_prices > 1
order by distinct_prices desc, segment_count desc, route_no, airplane_code, fare_conditions
limit 20;

\echo 'Class price multiplier evidence for fallback rules'

with ratios as (
    select
        economy.route_no,
        economy.airplane_code,
        business.price / economy.price as business_to_economy,
        comfort.price / economy.price as comfort_to_economy
    from d4_route_class_airplane_stats economy
    left join d4_route_class_airplane_stats business
        on business.route_no = economy.route_no
       and business.airplane_code = economy.airplane_code
       and business.fare_conditions = 'Business'
    left join d4_route_class_airplane_stats comfort
        on comfort.route_no = economy.route_no
       and comfort.airplane_code = economy.airplane_code
       and comfort.fare_conditions = 'Comfort'
    where economy.fare_conditions = 'Economy'
)
select
    count(*) filter (where business_to_economy = 2.0) as business_ratio_2,
    count(business_to_economy) as business_ratio_known,
    count(*) filter (where comfort_to_economy = 1.3) as comfort_ratio_13,
    count(comfort_to_economy) as comfort_ratio_known,
    min(business_to_economy) as min_business_ratio,
    max(business_to_economy) as max_business_ratio,
    min(comfort_to_economy) as min_comfort_ratio,
    max(comfort_to_economy) as max_comfort_ratio
from ratios;

-- This mirrors the final D4 rule before creating the persistent table in 02.
\echo 'Coverage for upcoming flight fare options with exact rules and class-ratio fallback'

with upcoming_fare_options as (
    select distinct
        f.flight_id,
        f.route_no,
        r.airplane_code,
        s.fare_conditions
    from flights f
    join routes r
        on r.route_no = f.route_no
       and r.validity @> f.scheduled_departure
    join seats s on s.airplane_code = r.airplane_code
    where f.scheduled_departure >= bookings.now()
),
priced_options as (
    select
        u.flight_id,
        u.route_no,
        u.airplane_code,
        u.fare_conditions,
        coalesce(
            exact.price,
            case
                when u.fare_conditions = 'Economy' then
                    coalesce(comfort.price / 1.3, business.price / 2.0)
                when u.fare_conditions = 'Comfort' then
                    coalesce(economy.price * 1.3, business.price * 0.65)
                when u.fare_conditions = 'Business' then
                    coalesce(economy.price * 2.0, comfort.price / 1.3 * 2.0)
            end
        ) as price,
        exact.price is not null as is_exact
    from upcoming_fare_options u
    left join d4_route_class_airplane_stats exact
        on exact.route_no = u.route_no
       and exact.airplane_code = u.airplane_code
       and exact.fare_conditions = u.fare_conditions
    left join d4_route_class_airplane_stats economy
        on economy.route_no = u.route_no
       and economy.airplane_code = u.airplane_code
       and economy.fare_conditions = 'Economy'
    left join d4_route_class_airplane_stats comfort
        on comfort.route_no = u.route_no
       and comfort.airplane_code = u.airplane_code
       and comfort.fare_conditions = 'Comfort'
    left join d4_route_class_airplane_stats business
        on business.route_no = u.route_no
       and business.airplane_code = u.airplane_code
       and business.fare_conditions = 'Business'
)
select
    count(*) as upcoming_fare_options,
    count(*) filter (where is_exact) as exact_options,
    count(*) filter (where not is_exact and price is not null) as inferred_options,
    count(price) as priced_options,
    count(*) - count(price) as missing_options
from priced_options;
