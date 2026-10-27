# XML

## What it is

XML is a text format for structured data. A file is a tree of **elements**. An element has an opening tag `<name>`, content, and a closing tag `</name>`. An element can hold text, other elements, or **attributes** written inside the opening tag (`<module name="Checker">`). A tag with no content can be written as one self-closing tag, `<relativePath/>`.

## Why this repository uses it

Two tools insist on it: Maven reads `pom.xml`, and Checkstyle reads its rule file. The repository does not choose XML; it follows the tools.

## Where it appears

| Path | What the XML describes |
| --- | --- |
| `pom.xml` (root and the three modules) | the Maven build |
| `config/checkstyle/checkstyle.xml` | which style rules Checkstyle enforces |
| `config/checkstyle/suppressions.xml` | the few places where a rule is switched off, each with the reason as a comment |

## How it works underneath

A program called a **parser** reads the text and builds the tree in memory. If a tag is not closed or the nesting is wrong, the parser stops with an error and says which line. Indentation does not matter to the parser; it only helps humans. Everything between `<!--` and `-->` is a **comment**, ignored by the program. Characters with a special meaning are written as **entities**: `&lt;` for `<`, `&amp;` for `&`.

## Walkthrough of this repository's use

The top of `pom.xml`:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
  <modelVersion>4.0.0</modelVersion>
```

The first line says "this is XML 1.0, saved as UTF-8". `<project ...>` is the root element: every XML document has exactly one root. The `xmlns` (XML namespace) and `xsi:schemaLocation` attributes name the **schema**, the document that lists which elements Maven allows and where; editors use it for completion. `<modelVersion>4.0.0</modelVersion>` is the version of the POM format and never changes.

The Checkstyle file shows nesting and attributes:

```xml
<module name="Checker">
  <property name="severity" value="warning"/>
  <module name="SuppressionFilter">
    <property name="file" value="${checkstyle.suppressions.file}"/>
  </module>
  <property name="charset" value="UTF-8"/>

  <module name="NewlineAtEndOfFile"/>
```

`<module name="Checker">` is the root rule group. Inside it, `<property name="..." value="..."/>` sets an option, and each nested `<module name="..."/>` switches on one rule. A rule that needs options has children. For example:

```xml
<module name="LocalVariableName">
  <property name="allowOneCharVarInForLoop" value="false"/>
  <property name="format" value="^[a-z][a-zA-Z0-9]+$"/>
</module>
```

This says: names of local variables must match the regular expression `^[a-z][a-zA-Z0-9]+$` (start with a lower-case letter, at least two characters) and a one-letter loop variable is not allowed.

The suppressions file starts each entry with a comment that gives the reason:

```xml
<!-- A Spring Boot entry point holds only a static main method. -->
<suppress checks="HideUtilityClassConstructor" files="[\\/]src[\\/]main[\\/].*Application\.java"/>
```

## Try it yourself

1. `xmllint --noout pom.xml` (installed with macOS) prints nothing when the file is well formed.
2. Remove a closing tag in a copy of the file and run the same command: it names the line.
3. `./mvnw help:effective-pom | head -50` prints the POM after inheritance from the parent: the same XML with the inherited parts filled in.

## Mistakes people make

- Forgetting to close a tag, or closing in the wrong order.
- Writing `&` or `<` in text without the entity.
- Putting a comment inside a tag.
- Treating indentation as meaningful (it is not, unlike YAML).

## Check yourself

1. What is an element? *An opening tag, content and a closing tag.*
2. What does `<relativePath/>` mean? *An element with no content, written as one tag.*
3. How many root elements can a document have? *One.*
4. What is a comment for, in `suppressions.xml`? *To state why a rule is switched off.*
5. Does indentation change the meaning of XML? *No.*

## Further reading

- XML 1.0 specification: https://www.w3.org/TR/xml/
- Maven POM reference: https://maven.apache.org/pom.html
- Checkstyle configuration: https://checkstyle.org/config.html
