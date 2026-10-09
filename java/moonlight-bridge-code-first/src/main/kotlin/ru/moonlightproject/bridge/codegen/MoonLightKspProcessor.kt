package ru.moonlightproject.bridge.codegen

import com.google.devtools.ksp.processing.SymbolProcessor
import com.google.devtools.ksp.processing.SymbolProcessorEnvironment
import com.google.devtools.ksp.processing.SymbolProcessorProvider
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.symbol.ClassKind
import com.google.devtools.ksp.symbol.KSAnnotated
import com.google.devtools.ksp.symbol.KSAnnotation
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSFunctionDeclaration
import com.google.devtools.ksp.symbol.KSType
import com.google.devtools.ksp.symbol.Nullability
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale
import java.util.regex.Pattern

/** KSP entry point for native Kotlin code-first contracts. */
public class MoonLightKspProcessorProvider : SymbolProcessorProvider {
    override fun create(environment: SymbolProcessorEnvironment): SymbolProcessor =
        MoonLightKspProcessor(environment)
}

private class MoonLightKspProcessor(
    private val environment: SymbolProcessorEnvironment,
) : SymbolProcessor {
    private var generated = false

    override fun process(resolver: Resolver): List<KSAnnotated> {
        if (generated) return emptyList()
        val contracts = resolver.getSymbolsWithAnnotation(CONTRACT).filterIsInstance<KSClassDeclaration>().toList()
        if (contracts.isEmpty()) return emptyList()
        if (contracts.size != 1) {
            environment.logger.error("exactly one @MoonLightContract is allowed per KSP invocation")
            return emptyList()
        }
        val output = environment.options["moonlight.output"]
        if (output.isNullOrBlank()) {
            environment.logger.error("KSP option moonlight.output=<file.proto> is required", contracts.single())
            return emptyList()
        }
        try {
            val path = Path.of(output)
            path.parent?.let(Files::createDirectories)
            Files.writeString(path, render(contracts.single()))
            generated = true
        } catch (error: IllegalArgumentException) {
            environment.logger.error(error.message ?: "cannot generate Protobuf", contracts.single())
        }
        return emptyList()
    }

    private fun render(contract: KSClassDeclaration): String {
        val settings = contract.annotation(CONTRACT)
        val protoPackage = settings.string("protoPackage")
        val javaPackage = settings.string("javaPackage")
        val declarations = contract.declarations.filterIsInstance<KSClassDeclaration>().toList()
        val lock = loadLock(environment.options["moonlight.schemaLock"])
        val messages = declarations.filter { it.hasAnnotation(MESSAGE) }.sortedBy { it.simpleName.asString() }
        val enumerations = declarations.filter { it.hasAnnotation(ENUMERATION) }.sortedBy { it.simpleName.asString() }
        val services = declarations.filter { it.hasAnnotation(SERVICE) }.sortedBy { it.simpleName.asString() }
        val hasPolicies = services.any { service ->
            service.declarations.filterIsInstance<KSFunctionDeclaration>().any { it.hasAnnotation(RPC) }
        }
        return buildString {
            append("syntax = \"proto3\";\n\npackage ").append(protoPackage).append(";\n\n")
            if (hasPolicies) append("import \"moonlight/bridge/options/v1/options.proto\";\n\n")
            append("option java_package = \"").append(javaPackage).append("\";\n")
            append("option java_multiple_files = true;\n\n")
            messages.forEach { appendMessage(protoPackage, it, lock) }
            enumerations.forEach { appendEnum(protoPackage, it, lock) }
            services.forEach { appendService(it) }
        }
    }

    private fun StringBuilder.appendMessage(
        protoPackage: String,
        message: KSClassDeclaration,
        lock: LockedSchema,
    ) {
        val name = message.simpleName.asString()
        val fields = message.primaryConstructor?.parameters
            ?: throw IllegalArgumentException("Kotlin message $name must use a primary constructor")
        val previous = lock.messages["$protoPackage.$name"] ?: LockedMessage.EMPTY
        val currentNames = fields.map { snakeCase(it.name?.asString() ?: error("unnamed parameter in $name")) }.toSet()
        val used = (previous.fields.values + previous.reservedNumbers).toMutableSet()
        append("message ").append(name).append(" {\n")
        previous.fields.entries.filter { it.key !in currentNames }.sortedBy { it.value }.forEach {
            append("  reserved ").append(it.value).append(";\n")
            append("  reserved \"").append(it.key).append("\";\n")
        }
        previous.reservedNumbers.sorted().forEach { append("  reserved ").append(it).append(";\n") }
        previous.reservedNames.sorted().forEach { append("  reserved \"").append(it).append("\";\n") }
        fields.forEach { field ->
            val fieldName = snakeCase(field.name?.asString() ?: error("unnamed parameter in $name"))
            val type = field.type.resolve().protoType("$name.$fieldName")
            val number = previous.fields[fieldName] ?: nextNumber(used, 1)
            used += number
            append("  ").append(type.label).append(type.name).append(' ')
                .append(fieldName).append(" = ").append(number).append(";\n")
        }
        append("}\n\n")
    }

    private fun StringBuilder.appendEnum(
        protoPackage: String,
        enumeration: KSClassDeclaration,
        lock: LockedSchema,
    ) {
        val name = enumeration.simpleName.asString()
        val sourceValues = enumeration.declarations.filterIsInstance<KSClassDeclaration>()
            .filter { it.classKind == ClassKind.ENUM_ENTRY }
            .map { it.simpleName.asString().uppercase(Locale.ROOT) }
            .toList()
        val previous = lock.enums["$protoPackage.$name"] ?: LockedEnum.EMPTY
        val used = (previous.values.values + previous.reservedNumbers).toMutableSet()
        val values = sourceValues.map { value ->
            val number = previous.values[value] ?: nextNumber(used, 0)
            used += number
            number to value
        }.sortedBy { it.first }
        require(values.firstOrNull()?.first == 0) {
            "enum $name must retain or declare an active zero-valued constant"
        }
        append("enum ").append(name).append(" {\n")
        previous.values.entries.filter { it.key !in sourceValues }.sortedBy { it.value }.forEach {
            append("  reserved ").append(it.value).append(";\n")
            append("  reserved \"").append(it.key).append("\";\n")
        }
        previous.reservedNumbers.sorted().forEach { append("  reserved ").append(it).append(";\n") }
        previous.reservedNames.sorted().forEach { append("  reserved \"").append(it).append("\";\n") }
        values.forEach { (number, value) ->
            append("  ").append(value).append(" = ").append(number).append(";\n")
        }
        append("}\n\n")
    }

    private fun StringBuilder.appendService(service: KSClassDeclaration) {
        val serviceName = service.simpleName.asString()
        append("service ").append(serviceName).append(" {\n")
        service.declarations.filterIsInstance<KSFunctionDeclaration>()
            .sortedBy { it.simpleName.asString() }
            .forEach { method -> appendMethod(serviceName, method) }
        append("}\n\n")
    }

    private fun StringBuilder.appendMethod(service: String, method: KSFunctionDeclaration) {
        require(method.parameters.size == 1) {
            "RPC $service.${method.simpleName.asString()} must have exactly one request parameter"
        }
        val request = method.parameters.single().type.resolve().declaration.simpleName.asString()
        val response = method.returnType?.resolve()?.declaration?.simpleName?.asString()
            ?: throw IllegalArgumentException("RPC $service.${method.simpleName.asString()} needs a response type")
        val policy = method.annotations.firstOrNull { it.qualifiedName() == RPC }
        val streaming = policy?.boolean("serverStreaming", false) == true
        append("  rpc ").append(upperCamel(method.simpleName.asString())).append('(').append(request)
            .append(") returns (").append(if (streaming) "stream " else "").append(response).append(')')
        if (policy == null) {
            append(";\n")
            return
        }
        append(" {\n    option (moonlight.bridge.options.v1.rpc_policy) = {\n")
        append("      timeout_ms: ").append(policy.int("timeoutMs", 2_000)).append('\n')
        append("      idle_timeout_ms: ").append(
            policy.int("idleTimeoutMs", if (streaming) 30_000 else 0),
        ).append('\n')
        append("      retry: { max_attempts: ").append(policy.int("maxAttempts", 1))
            .append(" initial_backoff_ms: ").append(policy.int("initialBackoffMs", 25))
            .append(" max_backoff_ms: ").append(policy.int("maxBackoffMs", 1_000))
            .append(" multiplier_milli: ").append(policy.int("multiplierMilli", 2_000)).append(" }\n")
        append("      idempotency: IDEMPOTENCY_").append(policy.string("idempotency", "unspecified").enumName()).append('\n')
        append("      max_request_bytes: ").append(policy.int("maxRequestBytes", 16 * 1024 * 1024)).append('\n')
        append("      max_response_bytes: ").append(policy.int("maxResponseBytes", 16 * 1024 * 1024)).append('\n')
        append("      compression: COMPRESSION_MODE_").append(policy.string("compression", "default").enumName()).append('\n')
        append("      trace_sample_per_million: ").append(policy.int("traceSamplePerMillion", 0)).append('\n')
        policy.strings("requiredScopes").forEach { append("      required_scopes: \"").append(it).append("\"\n") }
        append("    };\n  }\n")
    }

    private fun KSType.protoType(location: String): ProtoType {
        val qualified = declaration.qualifiedName?.asString()
            ?: throw IllegalArgumentException("unsupported Kotlin type for $location: $this")
        if (qualified == "kotlin.collections.List" || qualified == "kotlin.collections.Collection") {
            val inner = arguments.singleOrNull()?.type?.resolve()
                ?: throw IllegalArgumentException("collection type for $location needs one argument")
            return ProtoType("repeated ", inner.protoType(location).name)
        }
        val scalar = when (qualified) {
            "kotlin.String" -> "string"
            "kotlin.Boolean" -> "bool"
            "kotlin.Int" -> "int32"
            "kotlin.Long" -> "int64"
            "kotlin.Float" -> "float"
            "kotlin.Double" -> "double"
            "kotlin.ByteArray" -> "bytes"
            else -> declaration.simpleName.asString()
        }
        return ProtoType(if (nullability == Nullability.NULLABLE) "optional " else "", scalar)
    }

    private data class ProtoType(val label: String, val name: String)

    private data class LockedSchema(
        val messages: Map<String, LockedMessage>,
        val enums: Map<String, LockedEnum>,
    )

    private data class LockedMessage(
        val fields: Map<String, Int>,
        val reservedNumbers: Set<Int>,
        val reservedNames: Set<String>,
    ) {
        companion object { val EMPTY = LockedMessage(emptyMap(), emptySet(), emptySet()) }
    }

    private data class LockedEnum(
        val values: Map<String, Int>,
        val reservedNumbers: Set<Int>,
        val reservedNames: Set<String>,
    ) {
        companion object { val EMPTY = LockedEnum(emptyMap(), emptySet(), emptySet()) }
    }

    private fun loadLock(path: String?): LockedSchema {
        if (path.isNullOrBlank() || !Files.isRegularFile(Path.of(path))) {
            return LockedSchema(emptyMap(), emptyMap())
        }
        val json = Files.readString(Path.of(path))
        val messages = mutableMapOf<String, LockedMessage>()
        val messageNames = Pattern.compile("\\\"([^\\\"]+)\\\"\\s*:\\s*\\{\\s*\\\"fields\\\"").matcher(json)
        while (messageNames.find()) {
            val objectStart = json.indexOf('{', messageNames.start())
            val objectEnd = matchingBrace(json, objectStart)
            val valuesStart = json.indexOf('{', messageNames.end())
            val valuesEnd = matchingBrace(json, valuesStart)
            if (objectEnd < 0 || valuesEnd < 0) continue
            val fields = mutableMapOf<String, Int>()
            val fieldMatcher = LOCKED_FIELD.matcher(json.substring(valuesStart, valuesEnd + 1))
            while (fieldMatcher.find()) fields[fieldMatcher.group(2)] = fieldMatcher.group(1).toInt()
            val reservations = json.substring(valuesEnd + 1, objectEnd + 1)
            messages[messageNames.group(1)] = LockedMessage(
                fields, reservedNumbers(reservations), reservedNames(reservations),
            )
        }
        val enums = mutableMapOf<String, LockedEnum>()
        val enumNames = Pattern.compile(
            "\\\"([^\\\"]+)\\\"\\s*:\\s*\\{\\s*\\\"values\\\"\\s*:\\s*\\{",
        ).matcher(json)
        while (enumNames.find()) {
            val objectStart = json.indexOf('{', enumNames.start())
            val objectEnd = matchingBrace(json, objectStart)
            val valuesStart = enumNames.end() - 1
            val valuesEnd = matchingBrace(json, valuesStart)
            if (objectEnd < 0 || valuesEnd < 0) continue
            val values = mutableMapOf<String, Int>()
            val valueMatcher = LOCKED_ENUM_VALUE.matcher(json.substring(valuesStart, valuesEnd + 1))
            while (valueMatcher.find()) values[valueMatcher.group(2)] = valueMatcher.group(1).toInt()
            val reservations = json.substring(valuesEnd + 1, objectEnd + 1)
            enums[enumNames.group(1)] = LockedEnum(
                values, reservedNumbers(reservations), reservedNames(reservations),
            )
        }
        return LockedSchema(messages, enums)
    }

    private fun reservedNumbers(value: String): Set<Int> = buildSet {
        val matcher = LOCKED_RANGE.matcher(value)
        while (matcher.find()) {
            for (number in matcher.group(1).toInt() until matcher.group(2).toInt()) add(number)
        }
    }

    private fun reservedNames(value: String): Set<String> {
        val names = LOCKED_RESERVED_NAMES.matcher(value)
        if (!names.find()) return emptySet()
        return buildSet {
            val matcher = LOCKED_STRING.matcher(names.group(1))
            while (matcher.find()) add(matcher.group(1))
        }
    }

    private fun matchingBrace(value: String, start: Int): Int {
        if (start < 0) return -1
        var depth = 0
        for (index in start until value.length) {
            when (value[index]) {
                '{' -> depth++
                '}' -> if (--depth == 0) return index
            }
        }
        return -1
    }

    private fun nextNumber(used: Set<Int>, start: Int): Int {
        var candidate = start
        while (candidate in used || candidate in 19_000..19_999) candidate++
        return candidate
    }

    private companion object {
        const val CONTRACT = "ru.moonlightproject.bridge.codegen.MoonLightContract"
        const val MESSAGE = "ru.moonlightproject.bridge.codegen.MoonLightMessage"
        const val ENUMERATION = "ru.moonlightproject.bridge.codegen.MoonLightEnumeration"
        const val SERVICE = "ru.moonlightproject.bridge.codegen.MoonLightService"
        const val RPC = "ru.moonlightproject.bridge.codegen.MoonLightRpc"
        val LOCKED_FIELD: Pattern = Pattern.compile(
            "\\\"(\\d+)\\\"\\s*:\\s*\\{[^{}]*?\\\"name\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"",
        )
        val LOCKED_ENUM_VALUE: Pattern = Pattern.compile("\\\"(-?\\d+)\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"")
        val LOCKED_RANGE: Pattern = Pattern.compile(
            "\\\"start\\\"\\s*:\\s*(-?\\d+)\\s*,\\s*\\\"end\\\"\\s*:\\s*(-?\\d+)",
        )
        val LOCKED_RESERVED_NAMES: Pattern = Pattern.compile(
            "\\\"reserved_names\\\"\\s*:\\s*\\[(.*?)]",
            Pattern.DOTALL,
        )
        val LOCKED_STRING: Pattern = Pattern.compile("\\\"([^\\\"]+)\\\"")
    }
}

private fun KSAnnotated.hasAnnotation(name: String): Boolean = annotations.any { it.qualifiedName() == name }

private fun KSAnnotated.annotation(name: String): KSAnnotation =
    annotations.firstOrNull { it.qualifiedName() == name }
        ?: throw IllegalArgumentException("missing annotation $name")

private fun KSAnnotation.qualifiedName(): String? = annotationType.resolve().declaration.qualifiedName?.asString()

private fun KSAnnotation.value(name: String): Any? =
    arguments.firstOrNull { it.name?.asString() == name }?.value

private fun KSAnnotation.string(name: String, fallback: String? = null): String =
    value(name) as? String ?: fallback ?: throw IllegalArgumentException("annotation value $name is required")

private fun KSAnnotation.int(name: String, fallback: Int): Int = (value(name) as? Int) ?: fallback

private fun KSAnnotation.boolean(name: String, fallback: Boolean): Boolean = (value(name) as? Boolean) ?: fallback

private fun KSAnnotation.strings(name: String): List<String> =
    (value(name) as? List<*>)?.filterIsInstance<String>().orEmpty()

private fun String.enumName(): String = replace('-', '_').uppercase(Locale.ROOT)

private fun snakeCase(value: String): String = buildString {
    value.forEachIndexed { index, character ->
        if (character.isUpperCase() && index > 0) append('_')
        append(character.lowercaseChar())
    }
}

private fun upperCamel(value: String): String = value.split('_').joinToString("") {
    it.replaceFirstChar(Char::uppercaseChar)
}
