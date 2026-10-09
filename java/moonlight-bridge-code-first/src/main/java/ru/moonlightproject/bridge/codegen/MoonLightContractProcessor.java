package ru.moonlightproject.bridge.codegen;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.RoundEnvironment;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.RecordComponentElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.ArrayType;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.PrimitiveType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.tools.Diagnostic;

/** Emits a standard Protobuf schema from Java records/interfaces and Kotlin kapt stubs. */
public final class MoonLightContractProcessor extends AbstractProcessor {
    private static final Pattern LOCKED_FIELD = Pattern.compile(
        "\\\"(\\d+)\\\"\\s*:\\s*\\{[^{}]*?\\\"name\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"");
    private static final Pattern LOCKED_ENUM_VALUE = Pattern.compile(
        "\\\"(-?\\d+)\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"");
    private static final Pattern LOCKED_RANGE = Pattern.compile(
        "\\\"start\\\"\\s*:\\s*(-?\\d+)\\s*,\\s*\\\"end\\\"\\s*:\\s*(-?\\d+)");
    private static final Pattern LOCKED_RESERVED_NAMES = Pattern.compile(
        "\\\"reserved_names\\\"\\s*:\\s*\\[(.*?)]", Pattern.DOTALL);
    private static final Pattern LOCKED_STRING = Pattern.compile("\\\"([^\\\"]+)\\\"");
    private boolean generated;

    /** Creates a processor for one compiler invocation. */
    public MoonLightContractProcessor() { }

    @Override
    public Set<String> getSupportedAnnotationTypes() {
        return Set.of(MoonLightContract.class.getCanonicalName());
    }

    @Override
    public Set<String> getSupportedOptions() {
        return Set.of("moonlight.output", "moonlight.schemaLock");
    }

    @Override
    public SourceVersion getSupportedSourceVersion() {
        return SourceVersion.latestSupported();
    }

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment round) {
        if (generated || round.processingOver()) return false;
        var contracts = round.getElementsAnnotatedWith(MoonLightContract.class);
        if (contracts.isEmpty()) return false;
        if (contracts.size() != 1) {
            error(null, "exactly one @MoonLightContract is allowed per processor invocation");
            return true;
        }
        Element element = contracts.iterator().next();
        if (!(element instanceof TypeElement contract)) {
            error(element, "@MoonLightContract must annotate a type");
            return true;
        }
        String output = processingEnv.getOptions().get("moonlight.output");
        if (output == null || output.isBlank()) {
            error(contract, "processor option -Amoonlight.output=<file.proto> is required");
            return true;
        }
        try {
            String proto = generate(contract);
            Path path = Path.of(output);
            if (path.getParent() != null) Files.createDirectories(path.getParent());
            Files.writeString(path, proto);
            generated = true;
        } catch (IOException | IllegalArgumentException exception) {
            error(contract, exception.getMessage());
        }
        return true;
    }

    private String generate(TypeElement contract) throws IOException {
        MoonLightContract settings = contract.getAnnotation(MoonLightContract.class);
        Map<String, LockedMessage> locked = loadLockedMessages(
            processingEnv.getOptions().get("moonlight.schemaLock"));
        Map<String, LockedEnum> lockedEnums = loadLockedEnums(
            processingEnv.getOptions().get("moonlight.schemaLock"));
        List<TypeElement> messages = nestedTypes(contract, MoonLightMessage.class);
        List<TypeElement> enumerations = nestedTypes(contract, MoonLightEnumeration.class);
        List<TypeElement> services = nestedTypes(contract, MoonLightService.class);
        boolean policies = services.stream().flatMap(service -> service.getEnclosedElements().stream())
            .anyMatch(method -> method.getAnnotation(MoonLightRpc.class) != null);
        StringBuilder proto = new StringBuilder("syntax = \"proto3\";\n\n")
            .append("package ").append(settings.protoPackage()).append(";\n\n");
        if (policies) {
            proto.append("import \"moonlight/bridge/options/v1/options.proto\";\n\n");
        }
        proto.append("option java_package = \"").append(settings.javaPackage()).append("\";\n")
            .append("option java_multiple_files = true;\n\n");
        for (TypeElement message : messages) {
            emitMessage(proto, settings.protoPackage(), message, locked);
        }
        for (TypeElement enumeration : enumerations) {
            emitEnum(proto, settings.protoPackage(), enumeration, lockedEnums);
        }
        for (TypeElement service : services) emitService(proto, service);
        return proto.toString();
    }

    private <A extends java.lang.annotation.Annotation> List<TypeElement> nestedTypes(
            TypeElement contract, Class<A> annotation) {
        return contract.getEnclosedElements().stream()
            .filter(TypeElement.class::isInstance)
            .map(TypeElement.class::cast)
            .filter(type -> type.getAnnotation(annotation) != null)
            .sorted(Comparator.comparing(type -> type.getSimpleName().toString()))
            .toList();
    }

    private void emitMessage(StringBuilder proto, String pkg, TypeElement message,
            Map<String, LockedMessage> locked) {
        String name = message.getSimpleName().toString();
        List<? extends Element> fields;
        if (message.getKind() == ElementKind.RECORD) {
            fields = message.getRecordComponents();
        } else {
            fields = message.getEnclosedElements().stream()
                .filter(element -> element.getKind() == ElementKind.FIELD)
                .filter(element -> !element.getModifiers().contains(Modifier.STATIC))
                .toList();
        }
        LockedMessage previous = locked.getOrDefault(pkg + "." + name, LockedMessage.empty());
        Set<Integer> used = new HashSet<>(previous.fields().values());
        used.addAll(previous.reservedNumbers());
        Set<String> current = new HashSet<>();
        fields.forEach(field -> current.add(snakeCase(field.getSimpleName().toString())));
        proto.append("message ").append(name).append(" {\n");
        previous.fields().entrySet().stream().filter(entry -> !current.contains(entry.getKey()))
            .sorted(Map.Entry.comparingByValue()).forEach(entry -> proto
                .append("  reserved ").append(entry.getValue()).append(";\n")
                .append("  reserved \"").append(entry.getKey()).append("\";\n"));
        previous.reservedNumbers().stream().sorted()
            .forEach(number -> proto.append("  reserved ").append(number).append(";\n"));
        previous.reservedNames().stream().sorted()
            .forEach(reserved -> proto.append("  reserved \"").append(reserved).append("\";\n"));
        for (Element field : fields) {
            String fieldName = snakeCase(field.getSimpleName().toString());
            int number = previous.fields().getOrDefault(fieldName, nextNumber(used));
            used.add(number);
            TypeMirror type = field instanceof RecordComponentElement component
                ? component.asType() : field.asType();
            ProtoType protoType = mapType(type, name + "." + fieldName);
            proto.append("  ").append(protoType.label()).append(protoType.name()).append(' ')
                .append(fieldName).append(" = ").append(number).append(";\n");
        }
        proto.append("}\n\n");
    }

    private void emitService(StringBuilder proto, TypeElement service) {
        String serviceName = service.getSimpleName().toString();
        proto.append("service ").append(serviceName).append(" {\n");
        service.getEnclosedElements().stream().filter(ExecutableElement.class::isInstance)
            .map(ExecutableElement.class::cast)
            .sorted(Comparator.comparing(method -> method.getSimpleName().toString()))
            .forEach(method -> emitMethod(proto, serviceName, method));
        proto.append("}\n\n");
    }

    private void emitEnum(StringBuilder proto, String pkg, TypeElement enumeration,
            Map<String, LockedEnum> locked) {
        String name = enumeration.getSimpleName().toString();
        LockedEnum previous = locked.getOrDefault(pkg + "." + name, LockedEnum.empty());
        List<String> current = enumeration.getEnclosedElements().stream()
            .filter(element -> element.getKind() == ElementKind.ENUM_CONSTANT)
            .map(element -> element.getSimpleName().toString())
            .toList();
        Set<Integer> used = new HashSet<>(previous.values().values());
        used.addAll(previous.reservedNumbers());
        List<EnumValue> values = new ArrayList<>();
        for (String value : current) {
            int number = previous.values().getOrDefault(value, nextEnumNumber(used));
            used.add(number);
            values.add(new EnumValue(number, value));
        }
        values.sort(Comparator.comparingInt(EnumValue::number));
        if (!values.isEmpty() && values.getFirst().number() != 0) {
            throw new IllegalArgumentException("enum " + name
                + " must retain or declare an active zero-valued constant");
        }
        proto.append("enum ").append(name).append(" {\n");
        previous.values().entrySet().stream()
            .filter(entry -> !current.contains(entry.getKey()))
            .sorted(Map.Entry.comparingByValue())
            .forEach(entry -> proto.append("  reserved ").append(entry.getValue()).append(";\n")
                .append("  reserved \"").append(entry.getKey()).append("\";\n"));
        previous.reservedNumbers().stream().sorted()
            .forEach(number -> proto.append("  reserved ").append(number).append(";\n"));
        previous.reservedNames().stream().sorted()
            .forEach(reserved -> proto.append("  reserved \"").append(reserved).append("\";\n"));
        for (EnumValue value : values) {
            proto.append("  ").append(value.name()).append(" = ").append(value.number()).append(";\n");
        }
        proto.append("}\n\n");
    }

    private void emitMethod(StringBuilder proto, String service, ExecutableElement method) {
        if (method.getParameters().size() != 1) {
            throw new IllegalArgumentException("RPC " + service + "." + method.getSimpleName()
                + " must have exactly one request parameter");
        }
        String input = simpleDeclaredName(method.getParameters().getFirst().asType(), "request");
        TypeMirror returnType = unwrapAsync(method.getReturnType());
        String output = simpleDeclaredName(returnType, "response");
        MoonLightRpc policy = method.getAnnotation(MoonLightRpc.class);
        boolean streaming = policy != null && policy.serverStreaming();
        proto.append("  rpc ").append(upperCamel(method.getSimpleName().toString()))
            .append('(').append(input).append(") returns (")
            .append(streaming ? "stream " : "").append(output).append(')');
        if (policy == null) {
            proto.append(";\n");
            return;
        }
        proto.append(" {\n    option (moonlight.bridge.options.v1.rpc_policy) = {\n")
            .append("      timeout_ms: ").append(policy.timeoutMs()).append("\n")
            .append("      idle_timeout_ms: ").append(streaming && policy.idleTimeoutMs() == 0
                ? 30_000 : policy.idleTimeoutMs()).append("\n")
            .append("      retry: { max_attempts: ").append(policy.maxAttempts())
            .append(" initial_backoff_ms: ").append(policy.initialBackoffMs())
            .append(" max_backoff_ms: ").append(policy.maxBackoffMs())
            .append(" multiplier_milli: ").append(policy.multiplierMilli()).append(" }\n")
            .append("      idempotency: IDEMPOTENCY_").append(enumName(policy.idempotency())).append("\n")
            .append("      max_request_bytes: ").append(policy.maxRequestBytes()).append("\n")
            .append("      max_response_bytes: ").append(policy.maxResponseBytes()).append("\n")
            .append("      compression: COMPRESSION_MODE_").append(enumName(policy.compression())).append("\n")
            .append("      trace_sample_per_million: ").append(policy.traceSamplePerMillion()).append("\n");
        for (String scope : policy.requiredScopes()) {
            proto.append("      required_scopes: \"").append(scope).append("\"\n");
        }
        proto.append("    };\n  }\n");
    }

    private ProtoType mapType(TypeMirror type, String location) {
        if (type.getKind().isPrimitive()) {
            return new ProtoType("", switch (((PrimitiveType) type).getKind()) {
                case BOOLEAN -> "bool";
                case INT -> "int32";
                case LONG -> "int64";
                case FLOAT -> "float";
                case DOUBLE -> "double";
                default -> throw new IllegalArgumentException("unsupported JVM type for " + location + ": " + type);
            });
        }
        if (type.getKind() == TypeKind.ARRAY
                && ((ArrayType) type).getComponentType().getKind() == TypeKind.BYTE) {
            return new ProtoType("", "bytes");
        }
        if (!(type instanceof DeclaredType declared)) {
            throw new IllegalArgumentException("unsupported JVM type for " + location + ": " + type);
        }
        String qualified = ((TypeElement) declared.asElement()).getQualifiedName().toString();
        if (qualified.equals("java.util.List") || qualified.equals("java.util.Collection")) {
            return withLabel("repeated ", mapType(declared.getTypeArguments().getFirst(), location));
        }
        if (qualified.equals("java.util.Optional")) {
            return withLabel("optional ", mapType(declared.getTypeArguments().getFirst(), location));
        }
        String scalar = switch (qualified) {
            case "java.lang.String" -> "string";
            case "java.lang.Boolean" -> "bool";
            case "java.lang.Integer" -> "int32";
            case "java.lang.Long" -> "int64";
            case "java.lang.Float" -> "float";
            case "java.lang.Double" -> "double";
            default -> ((TypeElement) declared.asElement()).getSimpleName().toString();
        };
        String label = qualified.startsWith("java.lang.") && !qualified.equals("java.lang.String")
            ? "optional " : "";
        return new ProtoType(label, scalar);
    }

    private TypeMirror unwrapAsync(TypeMirror type) {
        if (type instanceof DeclaredType declared && !declared.getTypeArguments().isEmpty()) {
            String qualified = ((TypeElement) declared.asElement()).getQualifiedName().toString();
            if (qualified.equals("java.util.concurrent.CompletableFuture")
                    || qualified.equals("java.util.concurrent.Flow.Publisher")) {
                return declared.getTypeArguments().getFirst();
            }
        }
        return type;
    }

    private String simpleDeclaredName(TypeMirror type, String role) {
        if (!(type instanceof DeclaredType declared)) {
            throw new IllegalArgumentException("RPC " + role + " must be a declared message type: " + type);
        }
        return ((TypeElement) declared.asElement()).getSimpleName().toString();
    }

    private Map<String, LockedMessage> loadLockedMessages(String path) throws IOException {
        if (path == null || path.isBlank() || !Files.isRegularFile(Path.of(path))) return Map.of();
        String json = Files.readString(Path.of(path));
        Map<String, LockedMessage> result = new HashMap<>();
        Matcher messageName = Pattern.compile("\\\"([^\\\"]+)\\\"\\s*:\\s*\\{\\s*\\\"fields\\\"").matcher(json);
        while (messageName.find()) {
            int messageStart = json.indexOf('{', messageName.start());
            int messageEnd = matchingBrace(json, messageStart);
            int fieldsStart = json.indexOf('{', messageName.end());
            int fieldsEnd = matchingBrace(json, fieldsStart);
            if (messageStart < 0 || messageEnd < 0 || fieldsStart < 0 || fieldsEnd < 0) continue;
            Matcher field = LOCKED_FIELD.matcher(json.substring(fieldsStart, fieldsEnd + 1));
            Map<String, Integer> numbers = new HashMap<>();
            while (field.find()) numbers.put(field.group(2), Integer.parseInt(field.group(1)));
            String reservations = json.substring(fieldsEnd + 1, messageEnd + 1);
            result.put(messageName.group(1), new LockedMessage(
                numbers, reservedNumbers(reservations), reservedNames(reservations)));
        }
        return result;
    }

    private Map<String, LockedEnum> loadLockedEnums(String path) throws IOException {
        if (path == null || path.isBlank() || !Files.isRegularFile(Path.of(path))) return Map.of();
        String json = Files.readString(Path.of(path));
        Map<String, LockedEnum> result = new HashMap<>();
        Matcher enumName = Pattern.compile(
            "\\\"([^\\\"]+)\\\"\\s*:\\s*\\{\\s*\\\"values\\\"\\s*:\\s*\\{").matcher(json);
        while (enumName.find()) {
            int enumStart = json.indexOf('{', enumName.start());
            int enumEnd = matchingBrace(json, enumStart);
            int valuesStart = enumName.end() - 1;
            int valuesEnd = matchingBrace(json, valuesStart);
            if (enumStart < 0 || enumEnd < 0 || valuesEnd < 0) continue;
            Map<String, Integer> values = new HashMap<>();
            Matcher value = LOCKED_ENUM_VALUE.matcher(json.substring(valuesStart, valuesEnd + 1));
            while (value.find()) values.put(value.group(2), Integer.parseInt(value.group(1)));
            String reservations = json.substring(valuesEnd + 1, enumEnd + 1);
            result.put(enumName.group(1), new LockedEnum(
                values, reservedNumbers(reservations), reservedNames(reservations)));
        }
        return result;
    }

    private static int matchingBrace(String value, int start) {
        int depth = 0;
        for (int index = start; index < value.length(); index++) {
            if (value.charAt(index) == '{') depth++;
            else if (value.charAt(index) == '}' && --depth == 0) return index;
        }
        return -1;
    }

    private static Set<Integer> reservedNumbers(String value) {
        Set<Integer> result = new HashSet<>();
        Matcher range = LOCKED_RANGE.matcher(value);
        while (range.find()) {
            int start = Integer.parseInt(range.group(1));
            int end = Integer.parseInt(range.group(2));
            for (int number = start; number < end; number++) result.add(number);
        }
        return result;
    }

    private static Set<String> reservedNames(String value) {
        Set<String> result = new HashSet<>();
        Matcher names = LOCKED_RESERVED_NAMES.matcher(value);
        if (names.find()) {
            Matcher reservedName = LOCKED_STRING.matcher(names.group(1));
            while (reservedName.find()) result.add(reservedName.group(1));
        }
        return result;
    }

    private static int nextNumber(Set<Integer> used) {
        int candidate = 1;
        while (used.contains(candidate) || candidate >= 19_000 && candidate <= 19_999) candidate++;
        return candidate;
    }

    private static int nextEnumNumber(Set<Integer> used) {
        int candidate = 0;
        while (used.contains(candidate) || candidate >= 19_000 && candidate <= 19_999) candidate++;
        return candidate;
    }

    private static ProtoType withLabel(String label, ProtoType type) {
        if (!type.label().isEmpty()) throw new IllegalArgumentException("nested optional/repeated types are unsupported");
        return new ProtoType(label, type.name());
    }

    private static String snakeCase(String value) {
        StringBuilder result = new StringBuilder();
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (Character.isUpperCase(character) && index > 0) result.append('_');
            result.append(Character.toLowerCase(character));
        }
        return result.toString();
    }

    private static String upperCamel(String value) {
        StringBuilder result = new StringBuilder();
        boolean uppercase = true;
        for (char character : value.toCharArray()) {
            if (character == '_') { uppercase = true; continue; }
            result.append(uppercase ? Character.toUpperCase(character) : character);
            uppercase = false;
        }
        return result.toString();
    }

    private static String enumName(String value) {
        return value.replace('-', '_').toUpperCase(Locale.ROOT);
    }

    private void error(Element element, String message) {
        processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR, message, element);
    }

    private record ProtoType(String label, String name) { }
    private record EnumValue(int number, String name) { }
    private record LockedMessage(Map<String, Integer> fields, Set<Integer> reservedNumbers,
            Set<String> reservedNames) {
        private static LockedMessage empty() {
            return new LockedMessage(Map.of(), Set.of(), Set.of());
        }
    }
    private record LockedEnum(Map<String, Integer> values, Set<Integer> reservedNumbers,
            Set<String> reservedNames) {
        private static LockedEnum empty() {
            return new LockedEnum(Map.of(), Set.of(), Set.of());
        }
    }
}
