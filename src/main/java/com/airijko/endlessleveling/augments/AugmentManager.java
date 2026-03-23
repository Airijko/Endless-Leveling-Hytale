package com.airijko.endlessleveling.augments;

import com.airijko.endlessleveling.augments.types.*;
import com.airijko.endlessleveling.enums.PassiveCategory;
import com.airijko.endlessleveling.enums.PassiveTier;
import com.airijko.endlessleveling.managers.ConfigManager;
import com.airijko.endlessleveling.managers.PluginFilesManager;
import com.airijko.endlessleveling.managers.VersionRegistry;
import com.hypixel.hytale.logger.HytaleLogger;
import org.yaml.snakeyaml.Yaml;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class AugmentManager {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClassFull();

    // ── Factory registry ────────────────────────────────────────────────────

    private static final Map<String, Function<AugmentDefinition, Augment>> BUILTIN_FACTORIES = Map.ofEntries(
            Map.entry(AbsoluteFocusAugment.ID, AbsoluteFocusAugment::new),
            Map.entry(ArcaneCometAugment.ID, ArcaneCometAugment::new),
            Map.entry(ArcaneCataclysmAugment.ID, ArcaneCataclysmAugment::new),
            Map.entry(ArcaneInstabilityAugment.ID, ArcaneInstabilityAugment::new),
            Map.entry(ArcaneMasteryAugment.ID, ArcaneMasteryAugment::new),
            Map.entry(BailoutAugment.ID, BailoutAugment::new),
            Map.entry(CommonAugment.ID, CommonAugment::new),
            Map.entry(BruteForceAugment.ID, BruteForceAugment::new),
            Map.entry(DeathBombAugment.ID, DeathBombAugment::new),
            Map.entry(EndurePainAugment.ID, EndurePainAugment::new),
            Map.entry(BloodFrenzyAugment.ID, BloodFrenzyAugment::new),
            Map.entry(BloodEchoAugment.ID, BloodEchoAugment::new),
            Map.entry(BloodSurgeAugment.ID, BloodSurgeAugment::new),
            Map.entry(BloodthirsterAugment.ID, BloodthirsterAugment::new),
            Map.entry(BurnAugment.ID, BurnAugment::new),
            Map.entry(ConquerorAugment.ID, ConquerorAugment::new),
            Map.entry(CrippleAugment.ID, CrippleAugment::new),
            Map.entry(CriticalGuardAugment.ID, CriticalGuardAugment::new),
            Map.entry(CutdownAugment.ID, CutdownAugment::new),
            Map.entry(DrainAugment.ID, DrainAugment::new),
            Map.entry(ExecutionerAugment.ID, ExecutionerAugment::new),
            Map.entry(FleetFootworkAugment.ID, FleetFootworkAugment::new),
            Map.entry(FirstStrikeAugment.ID, FirstStrikeAugment::new),
            Map.entry(FortressAugment.ID, FortressAugment::new),
            Map.entry(FourLeafCloverAugment.ID, FourLeafCloverAugment::new),
            Map.entry(FrozenDomainAugment.ID, FrozenDomainAugment::new),
            Map.entry(GoliathAugment.ID, GoliathAugment::new),
            Map.entry(GiantSlayerAugment.ID, GiantSlayerAugment::new),
            Map.entry(GlassCannonAugment.ID, GlassCannonAugment::new),
            Map.entry(GraspOfTheUndyingAugment.ID, GraspOfTheUndyingAugment::new),
            Map.entry(HaymakerAugment.ID, HaymakerAugment::new),
            Map.entry(MagicBladeAugment.ID, MagicBladeAugment::new),
            Map.entry(MagicMissleAugment.ID, MagicMissleAugment::new),
            Map.entry(MissileShotAugment.ID, MissileShotAugment::new),
            Map.entry(ManaInfusionAugment.ID, ManaInfusionAugment::new),
            Map.entry(NestingDollAugment.ID, NestingDollAugment::new),
            Map.entry(OverdriveAugment.ID, OverdriveAugment::new),
            Map.entry(OverhealAugment.ID, OverhealAugment::new),
            Map.entry(PhaseRushAugment.ID, PhaseRushAugment::new),
            Map.entry(PhantomHitsAugment.ID, PhantomHitsAugment::new),
            Map.entry(PredatorAugment.ID, PredatorAugment::new),
            Map.entry(ProtectiveBubbleAugment.ID, ProtectiveBubbleAugment::new),
            Map.entry(RaidBossAugment.ID, RaidBossAugment::new),
            Map.entry(RagingMomentumAugment.ID, RagingMomentumAugment::new),
            Map.entry(RebirthAugment.ID, RebirthAugment::new),
            Map.entry(ReckoningAugment.ID, ReckoningAugment::new),
            Map.entry(SnipersReachAugment.ID, SnipersReachAugment::new),
            Map.entry(SoulReaverAugment.ID, SoulReaverAugment::new),
            Map.entry(SupersonicAugment.ID, SupersonicAugment::new),
            Map.entry(TankEngineAugment.ID, TankEngineAugment::new),
            Map.entry(TimeMasterAugment.ID, TimeMasterAugment::new),
            Map.entry(TitansMightAugment.ID, TitansMightAugment::new),
            Map.entry(TitansWisdomAugment.ID, TitansWisdomAugment::new),
            Map.entry(UndyingRageAugment.ID, UndyingRageAugment::new),
            Map.entry(VampiricStrikeAugment.ID, VampiricStrikeAugment::new),
            Map.entry(VampirismAugment.ID, VampirismAugment::new),
            Map.entry(WitherAugment.ID, WitherAugment::new));

    private static final Map<String, Function<AugmentDefinition, Augment>> EXTERNAL_FACTORIES = new ConcurrentHashMap<>();

    // ── Instance state ──────────────────────────────────────────────────────

    private final Yaml yaml;
    private final Path root;
    private final PluginFilesManager filesManager;
    private final boolean forceBuiltinAugments;
    private final boolean enableBuiltinAugments;
    private final Map<String, AugmentDefinition> externalDefinitions;
    private volatile Map<String, AugmentDefinition> fileDefinitions;
    private volatile Map<String, AugmentDefinition> cache;

    public AugmentManager(Path root, PluginFilesManager filesManager, ConfigManager configManager) {
        this.yaml = new Yaml();
        this.root = Objects.requireNonNull(root, "root");
        this.filesManager = Objects.requireNonNull(filesManager, "filesManager");
        Object forceFlag = configManager != null
                ? configManager.get("force_builtin_augments", Boolean.TRUE, false)
                : Boolean.TRUE;
        Object enableFlag = configManager != null
                ? configManager.get("enable_builtin_augments", Boolean.TRUE, false)
                : Boolean.TRUE;
        this.forceBuiltinAugments = parseBoolean(forceFlag, true);
        this.enableBuiltinAugments = parseBoolean(enableFlag, true);
        this.externalDefinitions = new LinkedHashMap<>();
        this.fileDefinitions = Collections.emptyMap();
        this.cache = Collections.emptyMap();
    }

    // ── Loading ─────────────────────────────────────────────────────────────

    public synchronized void load() {
        syncBuiltinAugmentsIfNeeded();
        if (!Files.isDirectory(root)) {
            LOGGER.atWarning().log("Augment directory %s does not exist or is not a directory", root);
            this.fileDefinitions = Collections.emptyMap();
            rebuildCache();
            return;
        }
        Map<String, AugmentDefinition> loaded = new LinkedHashMap<>();
        try (Stream<Path> paths = Files.list(root)) {
            List<Path> yamlFiles = paths
                    .filter(path -> !Files.isDirectory(path))
                    .filter(path -> {
                        String name = path.getFileName().toString().toLowerCase();
                        return name.endsWith(".yml") || name.endsWith(".yaml") || !name.contains(".");
                    })
                    .collect(Collectors.toList());
            for (Path file : yamlFiles) {
                try {
                    AugmentDefinition def = parseDefinition(file);
                    String augmentId = normalizeId(def.getId());
                    if (augmentId == null) {
                        LOGGER.atWarning().log("Skipping augment %s because its id is blank", file.getFileName());
                        continue;
                    }
                    loaded.put(augmentId, def);
                } catch (Exception ex) {
                    LOGGER.atWarning().withCause(ex).log("Failed to parse augment %s", file.getFileName());
                }
            }
        } catch (IOException ex) {
            LOGGER.atSevere().withCause(ex).log("Error reading augment directory %s", root);
        }
        this.fileDefinitions = Collections.unmodifiableMap(new LinkedHashMap<>(loaded));
        rebuildCache();
        LOGGER.atInfo().log("Loaded %d augments from %s", cache.size(), root);
    }

    // ── Queries ─────────────────────────────────────────────────────────────

    public Map<String, AugmentDefinition> getAugments() {
        return cache;
    }

    public AugmentDefinition getAugment(String id) {
        return cache.get(resolveLookupId(id));
    }

    public Augment createAugment(String id) {
        String lookup = resolveLookupId(id);
        AugmentDefinition definition = lookup == null ? null : cache.get(lookup);
        if (definition == null) {
            String candidate = id == null ? "null" : id.trim();
            LOGGER.atWarning().log("[AUGMENT] Failed to create augment: %s (not found in cache). Available augment count=%d", candidate, cache.size());
            if (!cache.isEmpty()) {
                LOGGER.atFine().log("[AUGMENT] Available augments: %s", String.join(", ", cache.keySet()));
            }
            return null;
        }
        return createFromDefinition(definition);
    }

    // ── External augment registration ───────────────────────────────────────

    public synchronized boolean canRegisterExternalAugment(String id, boolean replaceExisting) {
        String augmentId = normalizeId(id);
        if (augmentId == null) {
            return false;
        }
        return replaceExisting || !cache.containsKey(augmentId);
    }

    public synchronized void registerExternalAugment(AugmentDefinition definition) {
        Objects.requireNonNull(definition, "definition");
        String augmentId = requireValidId(definition.getId());
        boolean overridingFileDefinition = fileDefinitions.containsKey(augmentId);
        externalDefinitions.put(augmentId, definition);
        rebuildCache();
        if (overridingFileDefinition) {
            LOGGER.atInfo().log("Registered API augment '%s' and overrode the file-backed definition", augmentId);
        } else {
            LOGGER.atInfo().log("Registered API augment '%s'", augmentId);
        }
    }

    public synchronized boolean unregisterExternalAugment(String id) {
        String augmentId = normalizeId(id);
        if (augmentId == null) {
            return false;
        }
        AugmentDefinition removed = externalDefinitions.remove(augmentId);
        if (removed == null) {
            return false;
        }
        rebuildCache();
        LOGGER.atInfo().log("Unregistered API augment '%s'", augmentId);
        return true;
    }

    // ── Factory registration (external API) ─────────────────────────────────

    public static boolean canRegisterFactory(String id, boolean replaceExisting) {
        String augmentId = normalizeId(id);
        if (augmentId == null) {
            return false;
        }
        if (replaceExisting) {
            return true;
        }
        return !BUILTIN_FACTORIES.containsKey(augmentId) && !EXTERNAL_FACTORIES.containsKey(augmentId);
    }

    public static Function<AugmentDefinition, Augment> registerFactory(String id,
            Function<AugmentDefinition, Augment> factory) {
        String augmentId = requireValidId(id);
        return EXTERNAL_FACTORIES.put(augmentId, Objects.requireNonNull(factory, "factory"));
    }

    public static Function<AugmentDefinition, Augment> unregisterFactory(String id) {
        String augmentId = normalizeId(id);
        if (augmentId == null) {
            return null;
        }
        return EXTERNAL_FACTORIES.remove(augmentId);
    }

    // ── Private: factory instantiation ──────────────────────────────────────

    private static Augment createFromDefinition(AugmentDefinition definition) {
        if (definition == null) {
            return null;
        }
        String augmentId = normalizeId(definition.getId());
        Function<AugmentDefinition, Augment> factory = augmentId == null ? null : EXTERNAL_FACTORIES.get(augmentId);
        if (factory == null && augmentId != null) {
            factory = BUILTIN_FACTORIES.get(augmentId);
        }
        if (factory == null) {
            return new Augment(definition);
        }
        return Objects.requireNonNull(factory.apply(definition));
    }

    // ── Private: YAML parsing (absorbed from AugmentParser) ─────────────────

    @SuppressWarnings("unchecked")
    private AugmentDefinition parseDefinition(Path file) throws IOException {
        try (InputStream in = Files.newInputStream(file)) {
            Map<String, Object> root = yaml.load(in);
            if (root == null) {
                root = Collections.emptyMap();
            }
            String id = stringVal(root.get("id"), stripExtension(file.getFileName().toString()));
            String name = stringVal(root.get("name"), id);
            String description = stringVal(root.get("description"), "");
            PassiveTier tier = PassiveTier.fromConfig(root.get("tier"), PassiveTier.COMMON);
            PassiveCategory category = PassiveCategory.fromConfig(root.get("category"), null);
            boolean stackable = booleanVal(root.get("stackable"), false);
            boolean mobCompatible = booleanVal(root.get("mob_compatible"), true);
            Object passivesNode = root.getOrDefault("passives", Collections.emptyMap());
            Map<String, Object> passives = passivesNode instanceof Map<?, ?> m
                    ? (Map<String, Object>) m
                    : Collections.emptyMap();
            List<AugmentDefinition.UiSection> uiSections = parseUiSections(root);
            return new AugmentDefinition(id, name, tier, category, stackable, description, passives, uiSections, mobCompatible);
        }
    }

    @SuppressWarnings("unchecked")
    private List<AugmentDefinition.UiSection> parseUiSections(Map<String, Object> root) {
        Object uiNode = root.get("ui");
        List<AugmentDefinition.UiSection> sections = new ArrayList<>();
        if (uiNode instanceof Map<?, ?> uiRaw) {
            Object sectionsNode = ((Map<String, Object>) uiRaw).get("sections");
            if (sectionsNode instanceof List<?> sectionList) {
                for (Object sectionNode : sectionList) {
                    if (!(sectionNode instanceof Map<?, ?> rawSection)) {
                        continue;
                    }
                    Map<String, Object> section = (Map<String, Object>) rawSection;
                    String title = stringVal(section.get("title"), "");
                    String body = textVal(section.get("body"), "");
                    String color = stringVal(section.get("color"), "");
                    if (title.isBlank() && body.isBlank()) {
                        continue;
                    }
                    sections.add(new AugmentDefinition.UiSection(title, body, color));
                }
            }
        }
        return sections;
    }

    private String textVal(Object raw, String fallback) {
        if (raw instanceof String str && !str.isBlank()) {
            return str.trim();
        }
        if (raw == null) {
            return fallback;
        }
        String dumped = dumpYamlBlock(raw);
        return dumped.isBlank() ? fallback : dumped;
    }

    private String dumpYamlBlock(Object value) {
        if (value == null) {
            return "";
        }
        String dumped;
        try {
            dumped = yaml.dump(value);
        } catch (Exception ex) {
            dumped = String.valueOf(value);
        }
        if (dumped == null || dumped.isBlank()) {
            return "";
        }

        String normalized = dumped.replace("\r\n", "\n").replace('\r', '\n').trim();
        if (normalized.startsWith("---\n")) {
            normalized = normalized.substring(4).trim();
        }
        if (normalized.equals("...")) {
            return "";
        }
        if (normalized.endsWith("\n...")) {
            normalized = normalized.substring(0, normalized.length() - 4).trim();
        }
        return normalized;
    }

    // ── Private: helpers ────────────────────────────────────────────────────

    private String resolveLookupId(String id) {
        if (id == null || id.isBlank()) {
            return null;
        }
        String candidate = normalizeId(id);
        if (candidate == null) {
            return null;
        }

        String resolved = CommonAugment.resolveBaseAugmentId(candidate);
        if (resolved != null) {
            candidate = normalizeId(resolved);
        }

        if (cache.containsKey(candidate)) {
            return candidate;
        }

        // fallback: case-insensitive lookup if not already exact key
        for (String key : cache.keySet()) {
            if (key.equalsIgnoreCase(candidate)) {
                return key;
            }
        }

        return candidate;
    }

    private void syncBuiltinAugmentsIfNeeded() {
        if (!enableBuiltinAugments) {
            LOGGER.atInfo().log("Builtin augments are disabled (enable_builtin_augments=false)");
            return;
        }
        if (!forceBuiltinAugments) {
            return;
        }

        File augmentsFolder = filesManager.getAugmentsFolder();
        if (augmentsFolder == null) {
            LOGGER.atWarning().log("Augments folder is null; cannot sync built-in augments.");
            return;
        }

        int storedVersion = readAugmentsVersion(augmentsFolder);
        if (storedVersion == VersionRegistry.BUILTIN_AUGMENTS_VERSION) {
            return;
        }

        filesManager.archivePathIfExists(augmentsFolder.toPath(), "augments", "augments.version:" + storedVersion);
        clearDirectory(augmentsFolder.toPath());
        filesManager.exportResourceDirectory("augments", augmentsFolder, true);
        writeAugmentsVersion(augmentsFolder, VersionRegistry.BUILTIN_AUGMENTS_VERSION);
        LOGGER.atInfo().log("Synced built-in augments to version %d (force_builtin_augments=true)",
                VersionRegistry.BUILTIN_AUGMENTS_VERSION);
    }

    private int readAugmentsVersion(File augmentsFolder) {
        Path versionPath = augmentsFolder.toPath().resolve(VersionRegistry.AUGMENTS_VERSION_FILE);
        if (!Files.exists(versionPath)) {
            return -1;
        }
        try {
            String text = Files.readString(versionPath).trim();
            return Integer.parseInt(text);
        } catch (Exception e) {
            LOGGER.atWarning().log("Failed to read augments version file: %s", e.getMessage());
            return -1;
        }
    }

    private void writeAugmentsVersion(File augmentsFolder, int version) {
        Path versionPath = augmentsFolder.toPath().resolve(VersionRegistry.AUGMENTS_VERSION_FILE);
        try {
            Files.writeString(versionPath, Integer.toString(version));
        } catch (IOException e) {
            LOGGER.atWarning().log("Failed to write augments version file: %s", e.getMessage());
        }
    }

    private void clearDirectory(Path folder) {
        if (folder == null || !Files.exists(folder)) {
            return;
        }
        try (Stream<Path> stream = Files.walk(folder)) {
            stream.sorted(Comparator.reverseOrder())
                    .filter(path -> !path.equals(folder))
                    .forEach(path -> {
                        try {
                            Files.deleteIfExists(path);
                        } catch (IOException e) {
                            LOGGER.atWarning().log("Failed to delete %s: %s", path, e.getMessage());
                        }
                    });
        } catch (IOException e) {
            LOGGER.atWarning().log("Failed to clear augments directory: %s", e.getMessage());
        }
    }

    private boolean parseBoolean(Object value, boolean defaultValue) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        if (value instanceof String str) {
            return Boolean.parseBoolean(str.trim());
        }
        if (value instanceof Number number) {
            return number.intValue() != 0;
        }
        return defaultValue;
    }

    private void rebuildCache() {
        Map<String, AugmentDefinition> merged = new LinkedHashMap<>(fileDefinitions);
        merged.putAll(externalDefinitions);
        this.cache = Collections.unmodifiableMap(merged);
    }

    private static String requireValidId(String id) {
        String augmentId = normalizeId(id);
        if (augmentId == null) {
            throw new IllegalArgumentException("augment id cannot be null or blank");
        }
        return augmentId;
    }

    private static String normalizeId(String id) {
        if (id == null) {
            return null;
        }
        String trimmed = id.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        return trimmed.toLowerCase(Locale.ROOT);
    }

    private static String stripExtension(String filename) {
        int idx = filename.lastIndexOf('.');
        return idx > 0 ? filename.substring(0, idx) : filename;
    }

    private static String stringVal(Object raw, String fallback) {
        if (raw instanceof String str && !str.isBlank()) {
            return str.trim();
        }
        return fallback;
    }

    private static boolean booleanVal(Object raw, boolean fallback) {
        if (raw instanceof Boolean bool) {
            return bool;
        }
        if (raw instanceof String str && !str.isBlank()) {
            return Boolean.parseBoolean(str.trim());
        }
        if (raw instanceof Number number) {
            return number.intValue() != 0;
        }
        return fallback;
    }
}
