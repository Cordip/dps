use axum::{
    Json,
    extract::{Path, Query, State},
};
use sqlx::Row;

use crate::{
    app::AppState,
    error::AppError,
    models::{Airport, Coordinates, Page},
    validation::{
        Lang, LangQuery, ListQuery, Scope, parse_pagination, search_tokens, validate_airport_code,
    },
};

pub async fn list_airports(
    State(state): State<AppState>,
    Query(query): Query<ListQuery>,
) -> Result<Json<Page<Airport>>, AppError> {
    let lang = Lang::parse(query.lang.as_deref())?;
    let scope = Scope::parse(query.scope.as_deref())?;
    let pagination = parse_pagination(query.limit.as_deref(), query.offset.as_deref())?;
    let tokens = search_tokens(query.search.as_deref());

    let sql = format!(
        r#"
        with airport_rows as (
            select
                trim(a.airport_code)::text as airport_code,
                a.airport_name ->> $1 as airport_name,
                a.city ->> $1 as city,
                a.country ->> $1 as country,
                a.coordinates[0]::float8 as longitude,
                a.coordinates[1]::float8 as latitude,
                a.timezone,
                lower(trim(a.airport_code)::text) as airport_code_lc,
                lower(a.airport_name ->> $1) as airport_name_display,
                lower(a.city ->> $1) as city_display,
                lower(a.country ->> $1) as country_display,
                lower(a.airport_name ->> 'en') as airport_name_en,
                lower(a.airport_name ->> 'ru') as airport_name_ru,
                bookings.d6_ru_to_latin(a.airport_name ->> 'ru') as airport_name_ru_latin,
                lower(a.city ->> 'en') as city_en,
                lower(a.city ->> 'ru') as city_ru,
                bookings.d6_ru_to_latin(a.city ->> 'ru') as city_ru_latin,
                lower(a.country ->> 'en') as country_en,
                lower(a.country ->> 'ru') as country_ru,
                bookings.d6_ru_to_latin(a.country ->> 'ru') as country_ru_latin
            from bookings.airports_data a
            where {}
              and ($2::text is null or a.country ->> $1 = $2)
              and ($3::text is null or a.city ->> $1 = $3)
        ),
        matched as (
            select
                a.*,
                case
                    when cardinality($4::text[]) = 0 then 0::double precision
                    else (
                        select coalesce(sum(greatest(
                            case
                                when a.airport_code_lc = token.value
                                then 300.0 else 0.0
                            end,
                            case
                                when a.airport_code_lc like token.value || '%'
                                then 240.0 else 0.0
                            end,
                            case
                                when a.airport_name_display = token.value
                                  or a.city_display = token.value
                                  or a.country_display = token.value
                                then 180.0 else 0.0
                            end,
                            case
                                when a.airport_name_en = token.value
                                  or a.airport_name_ru = token.value
                                  or a.airport_name_ru_latin = token.value
                                  or a.city_en = token.value
                                  or a.city_ru = token.value
                                  or a.city_ru_latin = token.value
                                  or a.country_en = token.value
                                  or a.country_ru = token.value
                                  or a.country_ru_latin = token.value
                                then 160.0 else 0.0
                            end,
                            case
                                when a.airport_name_en like token.value || '%'
                                  or a.airport_name_ru like token.value || '%'
                                  or a.airport_name_ru_latin like token.value || '%'
                                  or a.city_en like token.value || '%'
                                  or a.city_ru like token.value || '%'
                                  or a.city_ru_latin like token.value || '%'
                                  or a.country_en like token.value || '%'
                                  or a.country_ru like token.value || '%'
                                  or a.country_ru_latin like token.value || '%'
                                then 120.0 else 0.0
                            end,
                            case
                                when a.airport_name_en like '%' || token.value || '%'
                                  or a.airport_name_ru like '%' || token.value || '%'
                                  or a.airport_name_ru_latin like '%' || token.value || '%'
                                  or a.city_en like '%' || token.value || '%'
                                  or a.city_ru like '%' || token.value || '%'
                                  or a.city_ru_latin like '%' || token.value || '%'
                                  or a.country_en like '%' || token.value || '%'
                                  or a.country_ru like '%' || token.value || '%'
                                  or a.country_ru_latin like '%' || token.value || '%'
                                then 80.0 else 0.0
                            end,
                            60.0 * greatest(
                                similarity(a.airport_code_lc, token.value),
                                similarity(a.airport_name_en, token.value),
                                similarity(a.airport_name_ru, token.value),
                                similarity(a.airport_name_ru_latin, token.value),
                                similarity(a.city_en, token.value),
                                similarity(a.city_ru, token.value),
                                similarity(a.city_ru_latin, token.value),
                                similarity(a.country_en, token.value),
                                similarity(a.country_ru, token.value),
                                similarity(a.country_ru_latin, token.value)
                            )
                        )), 0.0)
                        from unnest($4::text[]) token(value)
                    )
                end as search_rank
            from airport_rows a
            where cardinality($4::text[]) = 0
               or not exists (
                    select 1
                    from unnest($4::text[]) token(value)
                    where not (
                        a.airport_code_lc like '%' || token.value || '%'
                        or a.airport_name_en like '%' || token.value || '%'
                        or a.airport_name_ru like '%' || token.value || '%'
                        or a.airport_name_ru_latin like '%' || token.value || '%'
                        or a.city_en like '%' || token.value || '%'
                        or a.city_ru like '%' || token.value || '%'
                        or a.city_ru_latin like '%' || token.value || '%'
                        or a.country_en like '%' || token.value || '%'
                        or a.country_ru like '%' || token.value || '%'
                        or a.country_ru_latin like '%' || token.value || '%'
                        or a.airport_code_lc % token.value
                        or a.airport_name_en % token.value
                        or a.airport_name_ru % token.value
                        or a.airport_name_ru_latin % token.value
                        or a.city_en % token.value
                        or a.city_ru % token.value
                        or a.city_ru_latin % token.value
                        or a.country_en % token.value
                        or a.country_ru % token.value
                        or a.country_ru_latin % token.value
                    )
               )
        )
        select
            airport_code,
            airport_name,
            city,
            country,
            longitude,
            latitude,
            timezone
        from matched
        order by search_rank desc, airport_name, airport_code
        limit $5 offset $6
        "#,
        scope.airport_condition()
    );

    let rows = sqlx::query(sqlx::AssertSqlSafe(sql))
        .bind(lang.key())
        .bind(query.country)
        .bind(query.city)
        .bind(tokens)
        .bind(pagination.limit + 1)
        .bind(pagination.offset)
        .fetch_all(&state.pool)
        .await?;

    let has_more = rows.len() as i64 > pagination.limit;
    let items = rows
        .into_iter()
        .take(pagination.limit as usize)
        .map(row_to_airport)
        .collect();

    Ok(Json(Page {
        items,
        limit: pagination.limit,
        offset: pagination.offset,
        has_more,
    }))
}

pub async fn get_airport_by_code(
    State(state): State<AppState>,
    Path(airport_code): Path<String>,
    Query(query): Query<LangQuery>,
) -> Result<Json<Airport>, AppError> {
    let airport_code = validate_airport_code(&airport_code)?;
    let lang = Lang::parse(query.lang.as_deref())?;

    let row = sqlx::query(
        r#"
        select
            trim(a.airport_code)::text as airport_code,
            a.airport_name ->> $1 as airport_name,
            a.city ->> $1 as city,
            a.country ->> $1 as country,
            a.coordinates[0]::float8 as longitude,
            a.coordinates[1]::float8 as latitude,
            a.timezone
        from bookings.airports_data a
        where trim(a.airport_code)::text = $2
        "#,
    )
    .bind(lang.key())
    .bind(airport_code)
    .fetch_optional(&state.pool)
    .await?;

    let row =
        row.ok_or_else(|| AppError::not_found("airport_not_found", "Airport was not found"))?;
    Ok(Json(row_to_airport(row)))
}

pub async fn airport_exists(pool: &sqlx::PgPool, airport_code: &str) -> Result<bool, AppError> {
    let exists: bool = sqlx::query_scalar(
        "select exists (select 1 from bookings.airports_data where trim(airport_code)::text = $1)",
    )
    .bind(airport_code)
    .fetch_one(pool)
    .await?;
    Ok(exists)
}

fn row_to_airport(row: sqlx::postgres::PgRow) -> Airport {
    Airport {
        airport_code: row.get("airport_code"),
        airport_name: row.get("airport_name"),
        city: row.get("city"),
        country: row.get("country"),
        coordinates: Coordinates {
            longitude: row.get("longitude"),
            latitude: row.get("latitude"),
        },
        timezone: row.get("timezone"),
    }
}
