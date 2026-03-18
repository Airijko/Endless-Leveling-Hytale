# Endlessleveling

## Building

```bash
./gradlew clean build
```

Or on Windows:

```cmd
.\\gradlew.bat clean build
```

## Development

### Run Server with Plugin

```bash
./gradlew runServer
```

This will build your plugin, copy it to the server's mods folder, and start the Hytale server.

### Install Plugin Only (Hot Reload)

```bash
./gradlew installPlugin
```

This builds and copies the plugin to the server without starting it.

## Requirements

- **JDK 25** - Required for Gradle and compilation
- Gradle wrapper (included), or Gradle 8.10+ if you prefer a global install
- Hytale Server installation

The Hytale installation path is configured in gradle.properties.

## Git Bash note

In Git Bash, run wrapper scripts with ./ prefix:

```bash
./gradlew clean build
./gradlew.bat clean build
```

Do not use .\ in Git Bash. That syntax is for cmd/PowerShell.

## License

MIT

## Author

Airijko
