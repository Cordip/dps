create extension if not exists pg_trgm with schema public;

set search_path to bookings, public;

create or replace function bookings.d6_ru_to_latin(value text)
returns text
language plpgsql
immutable
parallel safe
as $$
declare
    result text := lower(coalesce(value, ''));
begin
    result := replace(result, 'щ', 'shch');
    result := replace(result, 'ш', 'sh');
    result := replace(result, 'ч', 'ch');
    result := replace(result, 'ц', 'ts');
    result := replace(result, 'ж', 'zh');
    result := replace(result, 'х', 'kh');
    result := replace(result, 'ю', 'yu');
    result := replace(result, 'я', 'ya');
    result := replace(result, 'ё', 'e');
    result := replace(result, 'й', 'y');
    result := replace(result, 'а', 'a');
    result := replace(result, 'б', 'b');
    result := replace(result, 'в', 'v');
    result := replace(result, 'г', 'g');
    result := replace(result, 'д', 'd');
    result := replace(result, 'е', 'e');
    result := replace(result, 'з', 'z');
    result := replace(result, 'и', 'i');
    result := replace(result, 'к', 'k');
    result := replace(result, 'л', 'l');
    result := replace(result, 'м', 'm');
    result := replace(result, 'н', 'n');
    result := replace(result, 'о', 'o');
    result := replace(result, 'п', 'p');
    result := replace(result, 'р', 'r');
    result := replace(result, 'с', 's');
    result := replace(result, 'т', 't');
    result := replace(result, 'у', 'u');
    result := replace(result, 'ф', 'f');
    result := replace(result, 'ы', 'y');
    result := replace(result, 'э', 'e');
    result := replace(result, 'ь', '');
    result := replace(result, 'ъ', '');

    return result;
end;
$$;

create index if not exists d6_airports_code_trgm_idx
    on airports_data using gin (lower(trim(airport_code)::text) gin_trgm_ops);

create index if not exists d6_airports_name_en_trgm_idx
    on airports_data using gin (lower(airport_name ->> 'en') gin_trgm_ops);

create index if not exists d6_airports_name_ru_trgm_idx
    on airports_data using gin (lower(airport_name ->> 'ru') gin_trgm_ops);

create index if not exists d6_airports_name_ru_latin_trgm_idx
    on airports_data using gin (bookings.d6_ru_to_latin(airport_name ->> 'ru') gin_trgm_ops);

create index if not exists d6_airports_city_en_trgm_idx
    on airports_data using gin (lower(city ->> 'en') gin_trgm_ops);

create index if not exists d6_airports_city_ru_trgm_idx
    on airports_data using gin (lower(city ->> 'ru') gin_trgm_ops);

create index if not exists d6_airports_city_ru_latin_trgm_idx
    on airports_data using gin (bookings.d6_ru_to_latin(city ->> 'ru') gin_trgm_ops);

create index if not exists d6_airports_country_en_trgm_idx
    on airports_data using gin (lower(country ->> 'en') gin_trgm_ops);

create index if not exists d6_airports_country_ru_trgm_idx
    on airports_data using gin (lower(country ->> 'ru') gin_trgm_ops);

create index if not exists d6_airports_country_ru_latin_trgm_idx
    on airports_data using gin (bookings.d6_ru_to_latin(country ->> 'ru') gin_trgm_ops);
