# OpenAPI Specification

Эта папка содержит многофайловую OpenAPI-спецификацию для D5 API.

Docker Compose файлы здесь не хранятся. Инструмент для просмотра документации
запускается из отдельного файла:

```text
compose/openapi.yml
```

## Structure

Правило проекта:

```text
source of truth: docs/openapi/openapi.yaml + paths/ + components/
generated artifact: docs/openapi/openapi.bundle.yaml
```

`openapi.bundle.yaml` нужен для совместимости с tools, которым надежнее
передавать single-file OpenAPI. Его нельзя редактировать вручную.

Главный entrypoint:

```text
openapi.yaml
```

Bundled файл для tools, которые плохо работают с external `$ref`:

```text
openapi.bundle.yaml
```

Редактировать нужно многофайловую спецификацию. `openapi.bundle.yaml`
перегенерируется командой:

```bash
node scripts/bundle_openapi.cjs
```

Endpoint-ы:

```text
paths/
  cities.yaml
  airports.yaml
  airports-by-code.yaml
  airport-schedule-arrivals.yaml
  airport-schedule-departures.yaml
  routes.yaml
  bookings.yaml
  bookings-by-ref.yaml
  check-ins.yaml
  ticket-boarding-passes.yaml
  ticket-flight-seats.yaml
```

Схемы:

```text
components/schemas/
  Airport.yaml
  AirportRef.yaml
  AirportListResponse.yaml
  BoardingPass.yaml
  BoardingPassListResponse.yaml
  Booking.yaml
  BookingCreateRequest.yaml
  BookingCreateSegment.yaml
  BookingCreateTrip.yaml
  BookingPassenger.yaml
  BookingPassengerInput.yaml
  BookingSegment.yaml
  BookingTrip.yaml
  CheckInRequest.yaml
  CheckInResponse.yaml
  CheckInSeatRequest.yaml
  City.yaml
  CityListResponse.yaml
  Coordinates.yaml
  Error.yaml
  Price.yaml
  RouteListResponse.yaml
  RouteOption.yaml
  RouteSegment.yaml
  SeatMapResponse.yaml
  SeatMapSeat.yaml
  ScheduledArrival.yaml
  ScheduledArrivalListResponse.yaml
  ScheduledDeparture.yaml
  ScheduledDepartureListResponse.yaml
  Weekday.yaml
```

`openapi.yaml` связывает эти файлы через официальные OpenAPI `$ref`.
`openapi.bundle.yaml` содержит тот же API в одном файле.

В D5/D6 authentication/authorization intentionally out of scope. API
проектируется как trusted admin API: все запросы считаются выполненными от
администратора системы. Поэтому в `openapi.yaml` явно задано `security: []`.
Это не passenger-facing public security model.

## Tools

Для работы со спецификацией есть отдельный compose:

```bash
docker compose -f compose/openapi.yml up -d scalar
```

Открыть:

```text
Scalar:      http://localhost:8083
Axum D6 API: http://localhost:3000/api/v1
```

Scalar использует `docs/openapi/openapi.bundle.yaml`. Чтобы в Scalar появился
реальный D6 server для отправки запросов, в `openapi.yaml` есть server:

```text
http://localhost:3000/api/v1
```

После изменения `openapi.yaml` нужно пересобрать bundle:

```bash
node scripts/bundle_openapi.cjs
```

## Run One Tool

Scalar:

```bash
docker compose -f compose/openapi.yml up -d scalar
```

Scalar использует:

```text
docs/openapi/openapi.bundle.yaml
```

Причина: Docker image Scalar корректно работает с mounted single-file OpenAPI
без external `$ref`.

## Stop

Остановить Scalar:

```bash
docker compose -f compose/openapi.yml down
```
