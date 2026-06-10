# XML Tasks

Source: `docs/xml_tasks.pdf`

## Task X1

- Parse the provided XML file using Java SAX or StAX.
- For each person, extract all available information:
  - id
  - first name
  - last name
  - gender
  - spouse
  - parents
  - children
  - siblings
- Validate consistency. The XML contains auxiliary markers such as the number of children.
- Prepare to write the collected data into another XML file where:
  - there is only one entry for each person;
  - each entry contains all collected information about that person;
  - the information is represented as structurally as possible.

For example, use more specific terms such as `brother` and `sister` instead of the generic `sibling`.

Each entry in the original file contains only part of the information about a person. There are multiple entries for the same person, and they may duplicate each other. The input format is not strict, so the same type of information may be represented in different ways.

## Task X2

- Define a strict XML Schema for representing the data extracted in X1 in a well-structured and strict form.
- Use `ID` and `IDREF` where possible.
- Extend the X1 solution so that it writes the extracted data using JAXB with schema validation.

## Task X3

Write an XSLT for the XML document produced in Task X2.

The XSLT must find a person who simultaneously has:

- parents;
- grandparents;
- siblings.

The XSLT must generate an HTML document showing information about:

- this person;
- his or her father;
- mother;
- brothers;
- sisters.

For each person listed above, the HTML must include:

- name and gender;
- names of father, mother, brothers, sisters, sons, and daughters;
- names of grandmother, grandfather, uncles, and aunts.

Hint: XSLT functions `position()` and `id()` are useful here.

## What The Tasks Require

### X1: Normalize Messy XML Into One Structured Person Record

Task X1 is the base task. The input XML is intentionally messy:

- the same person appears multiple times;
- one entry may contain only a name, another only children, another only spouse, and so on;
- fields may be represented by different tag names or formats;
- some records may duplicate data;
- count fields such as children count or siblings count should be checked against collected relationships.

The result of X1 should be a normalized XML file where every real person appears once and contains all known information in structured form.

In this project, the current processor already addresses this layer with StAX parsing, merging, validation warnings, relationship enrichment, and structured XML output.

### X2: Add A Formal Schema And JAXB Output

Task X2 is added on top of X1.

X1 only requires producing a structured XML. X2 requires making that structure strict and machine-validated:

- design an XSD schema for the output format;
- use XML `ID` and `IDREF` types for person identifiers and references where possible;
- write the output through JAXB classes instead of manual XML writer logic;
- validate the generated XML against the schema.

So X2 is not just “write XML”. It requires a formal contract for the output XML and code that produces schema-valid XML.

### X3: Transform The X2 XML Into HTML With XSLT

Task X3 is added on top of X2.

It assumes the X2 XML already exists and is schema-structured. Then an XSLT must:

- search for a person who has parents, grandparents, and siblings;
- generate an HTML report;
- include that person and immediate family members in the report;
- for each reported person, show not only direct relatives, but also grandparents, uncles, and aunts.

This task is mainly about navigating relationships in the structured XML. The hint about `id()` matters because `ID/IDREF` in the schema lets XSLT resolve references efficiently.

### Dependency Between Tasks

The tasks build on each other:

1. X1: parse and consolidate messy source XML into clean structured person data.
2. X2: define a strict XSD for that clean structure and generate schema-valid XML using JAXB.
3. X3: write XSLT over the X2 XML to produce an HTML family report.
