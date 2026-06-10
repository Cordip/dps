# lab1-v3

Normalizes dirty `people.xml` into structured `people_structured.xml`.

## Requirements

- Java 21
- Python 3
- Gradle wrapper from this directory

## Input

The original task input is stored as:

```text
docs/xml_task_X1.zip
```

The unpacked `people.xml` is intentionally not committed. Prepare it before
running the normalizer:

```text
python3 scripts/prepare_input.py
```

The script extracts `people.xml` into:

```text
app/input/people.xml
```

## Run

Run from this directory:

```text
./gradlew run
```

The output is written here:

```text
app/output/people_structured.xml
```

The program ignores command-line arguments. Missing input or validation errors
return a non-zero exit code and do not keep stale output.

## Check

Main tests:

```text
./gradlew test
```

