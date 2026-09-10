package com.jis.importer.parser;

/**
 * frontmatter 里 {@code related} 的一项。
 *
 * @param type RELATED / PREREQUISITE / CONTRAST / DEEPEN
 */
public record ParsedRelation(

        String targetSlug,

        String type
) {

    public static final String DEFAULT_TYPE = "RELATED";

    public static final java.util.Set<String> ALLOWED_TYPES = java.util.Set.of(
            "RELATED",
            "PREREQUISITE",
            "CONTRAST",
            "DEEPEN"
    );
}
