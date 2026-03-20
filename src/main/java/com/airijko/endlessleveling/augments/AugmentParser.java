package com.airijko.endlessleveling.augments;

import com.airijko.endlessleveling.enums.PassiveCategory;
import com.airijko.endlessleveling.enums.PassiveTier;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

public final class AugmentParser {

    private AugmentParser() {
    }

    @SuppressWarnings("unchecked")
    public static AugmentDefinition parse(Path file, Yaml yaml) throws IOException {
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
            Object passivesNode = root.getOrDefault("passives", Collections.emptyMap());
            Map<String, Object> passives = passivesNode instanceof Map<?, ?> m
                    ? (Map<String, Object>) m
                    : Collections.emptyMap();
                List<AugmentDefinition.UiSection> uiSections = parseUiSections(root, yaml);
            return new AugmentDefinition(id, name, tier, category, stackable, description, passives, uiSections);
        }
    }

    @SuppressWarnings("unchecked")
            private static List<AugmentDefinition.UiSection> parseUiSections(Map<String, Object> root, Yaml yaml) {
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
                    String body = textVal(section.get("body"), "", yaml);
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

    private static String textVal(Object raw, String fallback, Yaml yaml) {
        if (raw instanceof String str && !str.isBlank()) {
            return str.trim();
        }
        if (raw == null) {
            return fallback;
        }
        String dumped = dumpYamlBlock(raw, yaml);
        return dumped.isBlank() ? fallback : dumped;
    }

    private static String dumpYamlBlock(Object value, Yaml yaml) {
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
