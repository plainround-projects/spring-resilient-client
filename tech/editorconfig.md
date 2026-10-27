# EditorConfig

## What it is

EditorConfig is a tiny file, `.editorconfig`, that editors and IDEs read to apply the same basic settings: how to indent, which line endings to use, whether to trim trailing spaces. It needs no build step.

## Why this repository uses it

Different people use different editors. Without this file, a Makefile might get spaces where it needs tabs, or Markdown might lose its trailing double space.

## Where it appears

| Path | Role |
| --- | --- |
| `.editorconfig` | the settings, applied by the editor as you type |

## How it works underneath

1. The editor looks for `.editorconfig` in the file's folder and each parent up to the first one that says `root = true`.
2. Sections in square brackets are glob patterns for file names. Later sections override earlier ones.
3. The settings are only a request to the editor. No program in this repository enforces them.

## Walkthrough of this repository's use

```ini
root = true

[*]
charset = utf-8
end_of_line = lf
insert_final_newline = true
trim_trailing_whitespace = true
indent_style = space
indent_size = 4

[*.{yml,yaml,json,xml,toml}]
indent_size = 2

[Makefile]
indent_style = tab

[*.md]
trim_trailing_whitespace = false
max_line_length = off
```

- `root = true`: stop looking in parent folders.
- `[*]` applies to every file: UTF-8, Unix line endings (`lf`), a newline at the end of the file, trailing spaces removed, spaces not tabs, 4 spaces per indent level.
- `[*.{yml,yaml,json,xml,toml}]` overrides the indent size to 2 for those formats.
- `[Makefile]` uses real tabs, because `make` requires a tab before each recipe line.
- `[*.md]` keeps trailing spaces (two spaces at the end of a line mean a line break in some Markdown) and has no line-length limit.

## Try it yourself

1. Open any file in an editor with EditorConfig support (VS Code with the extension, IntelliJ built in) and press Tab: you get the configured indent.
2. Open the `Makefile` and notice the editor inserts a real tab.
3. `cat -A Makefile | head` shows `^I` for tabs.

## Mistakes people make

- Assuming it is enforced: it is not. Spotless and Checkstyle do the enforcing for Java.
- Putting spaces in a Makefile recipe: `make` fails with "missing separator".

## Check yourself

1. What does `root = true` do? *Stops the search in parent folders.*
2. Why does `[Makefile]` use tabs? *`make` requires tabs before recipe lines.*
3. Who enforces `.editorconfig`? *The editor, not the build.*
4. What does `end_of_line = lf` mean? *Unix line endings.*
5. Why keep trailing spaces in Markdown? *Two trailing spaces can mean a line break.*

## Further reading

- EditorConfig specification: https://spec.editorconfig.org/
