pub mod airports;
pub mod bookings;
pub mod checkins;
pub mod cities;
pub mod route_search;
pub mod schedules;

use chrono::{DateTime, SecondsFormat, Utc};
use chrono_tz::Tz;

pub fn trim_code(value: String) -> String {
    value.trim().to_owned()
}

pub fn weekday_token(day: i32) -> String {
    match day {
        1 => "MO",
        2 => "TU",
        3 => "WE",
        4 => "TH",
        5 => "FR",
        6 => "SA",
        7 => "SU",
        _ => "MO",
    }
    .to_owned()
}

pub fn format_rfc3339_at(value: DateTime<Utc>, timezone: &str) -> String {
    let tz = timezone.parse::<Tz>().unwrap_or(chrono_tz::UTC);
    value
        .with_timezone(&tz)
        .to_rfc3339_opts(SecondsFormat::Secs, false)
}

pub fn format_rfc3339_utc(value: DateTime<Utc>) -> String {
    value.to_rfc3339_opts(SecondsFormat::Secs, false)
}

pub fn iso_duration(duration: chrono::Duration) -> String {
    let seconds = duration.num_seconds().max(0);
    let hours = seconds / 3600;
    let minutes = (seconds % 3600) / 60;
    let secs = seconds % 60;

    let mut out = String::from("PT");
    if hours > 0 {
        out.push_str(&format!("{hours}H"));
    }
    if minutes > 0 {
        out.push_str(&format!("{minutes}M"));
    }
    if secs > 0 || (hours == 0 && minutes == 0) {
        out.push_str(&format!("{secs}S"));
    }
    out
}

pub fn money_to_cents(amount: &str) -> i64 {
    let normalized = crate::models::normalize_money(amount.to_owned());
    let mut parts = normalized.split('.');
    let units = parts.next().unwrap_or("0").parse::<i64>().unwrap_or(0);
    let cents = parts.next().unwrap_or("0").parse::<i64>().unwrap_or(0);
    units * 100 + cents
}

pub fn cents_to_money(cents: i64) -> String {
    format!("{}.{:02}", cents / 100, cents.abs() % 100)
}
