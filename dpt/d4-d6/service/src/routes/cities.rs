use axum::{Json, extract::Query, extract::State};
use sqlx::Row;

use crate::{
    app::AppState,
    error::AppError,
    models::{City, Page},
    validation::{Lang, ListQuery, Scope, parse_pagination, search_tokens},
};

pub async fn list_cities(
    State(state): State<AppState>,
    Query(query): Query<ListQuery>,
) -> Result<Json<Page<City>>, AppError> {
    let lang = Lang::parse(query.lang.as_deref())?;
    let scope = Scope::parse(query.scope.as_deref())?;
    let pagination = parse_pagination(query.limit.as_deref(), query.offset.as_deref())?;
    let tokens = search_tokens(query.search.as_deref());

    let sql = format!(
        r#"
        with city_rows as (
            select distinct
                a.city ->> $1 as city,
                a.country ->> $1 as country,
                lower(a.city ->> 'en') as city_en,
                lower(a.city ->> 'ru') as city_ru,
                bookings.d6_ru_to_latin(a.city ->> 'ru') as city_ru_latin,
                lower(a.country ->> 'en') as country_en,
                lower(a.country ->> 'ru') as country_ru,
                bookings.d6_ru_to_latin(a.country ->> 'ru') as country_ru_latin
            from bookings.airports_data a
            where {}
        ),
        matched as (
            select
                c.*,
                case
                    when cardinality($2::text[]) = 0 then 0::double precision
                    else (
                        select coalesce(sum(greatest(
                            case
                                when c.city_en = token.value
                                  or c.city_ru = token.value
                                  or c.city_ru_latin = token.value
                                  or c.country_en = token.value
                                  or c.country_ru = token.value
                                  or c.country_ru_latin = token.value
                                then 100.0 else 0.0
                            end,
                            case
                                when c.city_en like token.value || '%'
                                  or c.city_ru like token.value || '%'
                                  or c.city_ru_latin like token.value || '%'
                                  or c.country_en like token.value || '%'
                                  or c.country_ru like token.value || '%'
                                  or c.country_ru_latin like token.value || '%'
                                then 80.0 else 0.0
                            end,
                            case
                                when c.city_en like '%' || token.value || '%'
                                  or c.city_ru like '%' || token.value || '%'
                                  or c.city_ru_latin like '%' || token.value || '%'
                                  or c.country_en like '%' || token.value || '%'
                                  or c.country_ru like '%' || token.value || '%'
                                  or c.country_ru_latin like '%' || token.value || '%'
                                then 60.0 else 0.0
                            end,
                            40.0 * greatest(
                                similarity(c.city_en, token.value),
                                similarity(c.city_ru, token.value),
                                similarity(c.city_ru_latin, token.value),
                                similarity(c.country_en, token.value),
                                similarity(c.country_ru, token.value),
                                similarity(c.country_ru_latin, token.value)
                            )
                        )), 0.0)
                        from unnest($2::text[]) token(value)
                    )
                end as search_rank
            from city_rows c
            where cardinality($2::text[]) = 0
               or not exists (
                    select 1
                    from unnest($2::text[]) token(value)
                    where not (
                        c.city_en like '%' || token.value || '%'
                        or c.city_ru like '%' || token.value || '%'
                        or c.city_ru_latin like '%' || token.value || '%'
                        or c.country_en like '%' || token.value || '%'
                        or c.country_ru like '%' || token.value || '%'
                        or c.country_ru_latin like '%' || token.value || '%'
                        or c.city_en % token.value
                        or c.city_ru % token.value
                        or c.city_ru_latin % token.value
                        or c.country_en % token.value
                        or c.country_ru % token.value
                        or c.country_ru_latin % token.value
                    )
               )
        )
        select city, country
        from matched
        order by search_rank desc, city, country
        limit $3 offset $4
        "#,
        scope.airport_condition()
    );

    let rows = sqlx::query(sqlx::AssertSqlSafe(sql))
        .bind(lang.key())
        .bind(tokens)
        .bind(pagination.limit + 1)
        .bind(pagination.offset)
        .fetch_all(&state.pool)
        .await?;

    let has_more = rows.len() as i64 > pagination.limit;
    let items = rows
        .into_iter()
        .take(pagination.limit as usize)
        .map(|row| City {
            city: row.get("city"),
            country: row.get("country"),
        })
        .collect();

    Ok(Json(Page {
        items,
        limit: pagination.limit,
        offset: pagination.offset,
        has_more,
    }))
}
