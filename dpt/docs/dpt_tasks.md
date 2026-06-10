# DPT Tasks

## Task D1

Create an ER-diagram for the schema defined per task X2.

Express as many restrictions as possible via the domain constraints and relation cardinalities.

## Task D2

Define the relational model for the schema defined per task X2.

Express as many restrictions as possible via the domain, uniqueness, and foreign key constraints. Carefully choose whether to permit or prohibit the NULL values for each attribute.

## Task D3

Write a program that loads the data from task X2 into a PostgreSQL database with the schema defined in task D2.

The import should be performed in two phases:

- Streaming phase
- Normalization phase

Streaming phase requirements:

- The streaming phase should not buffer the data in memory.
- Rely on the SAX parser to avoid scanning the whole document before injecting the data into the database.
- The streaming phase must support multiple simultaneous threads.
- The number of threads to use, `N`, should be a program runtime parameter.
- Split the file into `N` segments of approximately the same size:
  - Guess the section points by dividing the file length by `N`.
  - Adjust the section points as necessary to avoid splitting the `<person>` elements.
- It is recommended to rely on a "temporary" database schema with little or no restrictions during the streaming phase to avoid foreign-key lookup failures.

Normalization phase requirements:

- The normalization phase should be performed via SQL statements over the data imported by the streaming phase.
- It should enforce all the constraints defined in the target database schema.
- Consider creating some indexes on the "raw" imported data on the early steps of the normalization phase to speed up the lookups during normalization.

---

## Intro

Tasks in this block are built upon the Flights database: [https://postgrespro.ru/education/demodb](https://postgrespro.ru/education/demodb). Choose the database size based on the space availability.

## Task D4

Restore the price information for each flight based on the past bookings, and build the pricing rule table that determines the prices for all upcoming flights.

## Task D5

Design the RESTful web service to handle the following requests:

- List all the available source and destination cities.
- List all the available source and destination airports.
- List the airports within a city.
- List the inbound schedule for an airport:
  - Days of week
  - Time of arrival
  - Flight no
  - Origin
- List the outbound schedule for an airport:
  - Days of week
  - Time of departure
  - Flight no
  - Destination
- List the routes connecting two points.
  - A point might be either an airport or a city. In the latter case, search for the flights connecting any airports within the city.
  - The mandatory "departure date" parameter limits the flights by the ones departing between 0:00:00 of the specified date and 0:00:00 of the next date.
  - The "booking class" parameter should be one of the following values: `Economy`, `Comfort`, `Business`.
  - An additional parameter limits the number of connections: 0 (direct), 1, 2, 3, unbound.
- Create a booking for a selected route for a single passenger.
- Online check-in for a flight.

## Task D6

Implement the RESTful web service described above. Consider adding the appropriate indexes to make the requests reasonably fast.
