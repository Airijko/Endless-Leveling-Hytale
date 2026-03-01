# Language Files

EndlessLeveling supports language packs in this folder.

## How it works

- Set the active locale in `config.yml`:
  - `language.locale: "en_US"`
  - `language.fallback_locale: "en_US"`
- Each locale is a YAML file named `<locale>.yml` (example: `en_US.yml`, `es_ES.yml`).
- Missing keys automatically fall back to the fallback locale and then to built-in defaults in code.

## Create your own translation

1. Copy `en_US.yml` to a new file, for example `es_ES.yml`.
2. Translate the values (right side), keep the keys unchanged.
3. Keep placeholders like `{0}`, `{1}` exactly as-is.
4. Set `language.locale` in `config.yml` to your locale file name (without `.yml`).
5. Run plugin reload (`/skills reload`) or restart server.

## Notes

- Values can include colorless plain text only; color is currently handled by code.
- You can organize keys in nested YAML sections.
- This language system is designed to be expanded over time as more UI and messages are migrated.

## Player language selection

- Players can set their own language with:
  - `/skills language <locale>`
- Example:
  - `/skills language en_US`
