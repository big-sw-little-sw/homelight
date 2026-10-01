package io.github.bigswlittlesw.homelight.config;

import com.fasterxml.jackson.annotation.JacksonAnnotationsInside;
import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.annotation.Nulls;
import io.github.bigswlittlesw.homelight.config.CandidateDiagnostic.Kind;
import org.snakeyaml.engine.v2.exceptions.MarkedYamlEngineException;
import tools.jackson.core.JacksonException;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.core.StreamReadConstraints;
import tools.jackson.core.TokenStreamContext;
import tools.jackson.core.TokenStreamLocation;
import tools.jackson.core.exc.StreamConstraintsException;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.cfg.CoercionAction;
import tools.jackson.databind.cfg.CoercionInputShape;
import tools.jackson.databind.deser.jdk.StringDeserializer;
import tools.jackson.databind.exc.InvalidFormatException;
import tools.jackson.databind.exc.InvalidNullException;
import tools.jackson.databind.exc.MismatchedInputException;
import tools.jackson.databind.exc.UnrecognizedPropertyException;
import tools.jackson.databind.module.SimpleModule;
import tools.jackson.dataformat.yaml.YAMLFactory;
import tools.jackson.dataformat.yaml.YAMLMapper;
import tools.jackson.dataformat.yaml.YAMLParser;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/// One YAML document, checked and then bound into a file-shape record with Jackson. Configuration and
/// candidate lists share it.
///
/// - Keys are kebab-case component names. Unknown and duplicate keys are rejected.
/// - Any scalar binds as text. A null, empty or blank scalar binds as absent (`null`); [Required]
///   components reject absent values. List items are never absent.
/// - Aliases are rejected; anchors and tags are ignored, and tags never construct objects.
/// - Nesting depth and string and key length are bounded, since candidate lists are written by others.
///
/// Failures are [Violation]s whose message names the value by dotted path, e.g.
/// `Unknown key homelight.relocations[0].x`, with its line and column.
final class YamlDocument {
    static final int MAX_DEPTH = 8;
    /// Counted in UTF-16 code units, as Jackson counts.
    static final int MAX_STRING_LENGTH = 4_096;

    private static final ObjectMapper MAPPER = YAMLMapper.builder(YAMLFactory.builder()
                    .streamReadConstraints(StreamReadConstraints.builder()
                            .maxNestingDepth(MAX_DEPTH)
                            .maxStringLength(MAX_STRING_LENGTH)
                            .maxNameLength(MAX_STRING_LENGTH)
                            .build())
                    .build())
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .propertyNamingStrategy(PropertyNamingStrategies.KEBAB_CASE)
            .changeDefaultNullHandling(nulls -> nulls.withContentNulls(Nulls.FAIL))
            // Blank text where a mapping, list or policy belongs is absent too; TextDeserializer covers strings.
            .withCoercionConfigDefaults(coercion -> coercion.setAcceptBlankAsEmpty(true)
                    .setCoercion(CoercionInputShape.EmptyString, CoercionAction.AsNull))
            .addModule(new SimpleModule().addDeserializer(String.class, new TextDeserializer()))
            .build();

    private final String text;
    /// Start of each key and value by dotted path. Binding reports some failures without a position, or
    /// at the end of a mapping, so failures are placed from these instead.
    private final Map<String, Position> keys = new HashMap<>(), values = new HashMap<>();

    /// Marks a component that must hold a value that is not absent.
    @Retention(RetentionPolicy.RUNTIME)
    @JacksonAnnotationsInside
    @JsonSetter(nulls = Nulls.FAIL)
    @interface Required {
    }

    /// One-based.
    record Position(int line, int column) {
        static final Position NONE = new Position(0, 0);
    }

    /// A rejected document. `location` is the dotted path of the mapping or list holding the failing value
    /// and `key` its key, empty when none applies.
    static final class Violation extends RuntimeException {
        final Kind kind;
        final Position position;
        final String location, key;
        /// As far as it was read.
        final YamlDocument document;

        private Violation(YamlDocument document, Kind kind, Position position, String location, String key,
                          String message) {
            super(message);
            this.kind = kind;
            this.position = position;
            this.location = location;
            this.key = key;
            this.document = document;
        }

        /// The message prefixed with its position, as configuration errors show it.
        String positioned() {
            return (position.line() > 0 ? "Line " + position.line() + ", column " + position.column() + ": " : "")
                    + getMessage();
        }
    }

    /// Reads every token, before binding stops at the first value of the wrong shape: syntax, limits,
    /// aliases and duplicate keys are checked across the whole document.
    static YamlDocument parse(String text) {
        var document = new YamlDocument(text);
        var parser = (YAMLParser) MAPPER.createParser(text);
        try (parser) {
            for (var token = parser.nextToken(); token != null; token = parser.nextToken()) {
                if (token.isStructEnd()) continue;
                var context = token.isStructStart() ? parser.streamReadContext().getParent() : parser.streamReadContext();
                var path = path(context);
                var at = position(parser.currentTokenLocation());
                // Jackson would read an alias as its anchor's name; refusing aliases also rules out expansion bombs.
                if (parser.isCurrentAlias()) {
                    throw new Violation(document, Kind.SCHEMA, at, "", "", "Aliases are not supported");
                }
                if (token != JsonToken.PROPERTY_NAME) {
                    // Paths identify values only within one document.
                    if (document.values.putIfAbsent(path, at) != null && path.isEmpty()) {
                        throw new Violation(document, Kind.SCHEMA, at, "", "", "Only one document is allowed");
                    }
                } else if (document.keys.putIfAbsent(path, at) != null) {
                    throw new Violation(document, Kind.SCHEMA, at, path(context.getParent()), context.currentName(),
                            "Duplicate key " + path);
                }
            }
        } catch (StreamConstraintsException exception) {
            var tooDeep = parser.streamReadContext().getNestingDepth() > MAX_DEPTH;
            throw new Violation(document, Kind.LIMIT, position(parser.currentTokenLocation()), "", "", tooDeep
                    ? "Nesting exceeds depth " + MAX_DEPTH : "Key or value exceeds " + MAX_STRING_LENGTH + " characters");
        } catch (JacksonException exception) {
            // snakeyaml's problem and mark are shorter than Jackson's message, which repeats the excerpt.
            throw exception.getCause() instanceof MarkedYamlEngineException yaml
                    ? new Violation(document, Kind.SYNTAX, yaml.getProblemMark().map(mark ->
                            new Position(mark.getLine() + 1, mark.getColumn() + 1)).orElse(Position.NONE), "", "",
                            "Malformed YAML: " + yaml.getProblem())
                    : new Violation(document, Kind.SYNTAX, position(exception.getLocation()), "", "",
                            "Malformed YAML: " + exception.getOriginalMessage());
        }
        return document;
    }

    private YamlDocument(String text) {
        this.text = text;
    }

    /// Returns `null` for a document without content.
    <T> T bind(Class<T> type) {
        if (values.isEmpty()) return null;
        try {
            return MAPPER.readValue(text, type);
        } catch (JacksonException exception) {
            throw violation(exception);
        }
    }

    /// The start of the value at `path`, or [Position#NONE].
    Position at(String path) {
        return values.getOrDefault(path, Position.NONE);
    }

    /// The number of items in the list at `path`; zero when there is none.
    int size(String path) {
        int size = 0;
        while (values.containsKey(path + "[" + size + "]")) size++;
        return size;
    }

    /// Words a binding failure for people, by the value's path and never Java type names.
    private Violation violation(JacksonException exception) {
        var path = new ArrayList<String>();
        for (var reference : exception.getPath()) {
            path.add(reference.getPropertyName() != null ? reference.getPropertyName() : "[" + reference.getIndex() + "]");
        }
        var key = path.isEmpty() || path.getLast().startsWith("[") ? "" : path.getLast();
        var location = dotted(key.isEmpty() ? path : path.subList(0, path.size() - 1));
        var name = dotted(path);
        var at = switch (exception) {
            case UnrecognizedPropertyException ignored -> keys.get(name);
            case InvalidNullException ignored when !key.isEmpty() -> values.get(location);  // the mapping lacking it
            default -> values.get(name);
        };
        var message = switch (exception) {
            case UnrecognizedPropertyException ignored -> "Unknown key " + name;
            case InvalidNullException ignored when !key.isEmpty() -> "Missing required key " + name;
            case InvalidNullException ignored -> "Missing required value " + name;
            case InvalidFormatException invalid when invalid.getTargetType().isEnum() -> "Invalid value '"
                    + invalid.getValue() + "' for " + name + "; expected one of " + choices(invalid.getTargetType());
            case MismatchedInputException mismatch -> "Expected " + shape(mismatch.getTargetType()) + " for "
                    + (name.isEmpty() ? "the document" : name);
            default -> exception.getOriginalMessage();
        };
        return new Violation(this, Kind.SCHEMA, at != null ? at : position(exception.getLocation()), location, key,
                message);
    }

    private static String path(TokenStreamContext context) {
        if (context == null || context.inRoot()) return "";
        var parent = path(context.getParent());
        if (context.inArray()) return parent + "[" + context.getCurrentIndex() + "]";
        return parent.isEmpty() ? context.currentName() : parent + "." + context.currentName();
    }

    private static String dotted(List<String> path) {
        var text = new StringBuilder();
        for (var segment : path) {
            text.append(text.isEmpty() || segment.startsWith("[") ? "" : ".").append(segment);
        }
        return text.toString();
    }

    /// Jackson marks an unknown position with -1.
    private static Position position(TokenStreamLocation location) {
        return location == null || location.getLineNr() < 1 ? Position.NONE
                : new Position(location.getLineNr(), location.getColumnNr());
    }

    private static String shape(Class<?> type) {
        if (type == String.class || type.isEnum()) return "a string";
        if (Collection.class.isAssignableFrom(type)) return "a list";
        return "a mapping";
    }

    /// The spellings of an enum's constants, as its `@JsonValue` writes them.
    private static String choices(Class<?> type) {
        return Arrays.stream(type.getEnumConstants())
                .map(constant -> MAPPER.convertValue(constant, String.class))
                .collect(Collectors.joining(", "));
    }

    /// Text from any scalar, as written; blank text is absent.
    private static final class TextDeserializer extends StringDeserializer {
        @Override
        public String deserialize(JsonParser parser, DeserializationContext context) {
            if (parser.currentToken().isScalarValue()) {
                var text = parser.getString();
                return text == null || text.isBlank() ? null : text;
            }
            return super.deserialize(parser, context);
        }
    }
}
