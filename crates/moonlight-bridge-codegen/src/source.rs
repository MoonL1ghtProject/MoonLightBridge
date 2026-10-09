use serde_json::Value;
use std::{
    collections::{BTreeMap, BTreeSet},
    fmt::Write as _,
    fs, io,
    path::PathBuf,
};
use syn::{
    Attribute, Fields, FnArg, GenericArgument, Item, ItemMod, ItemStruct, ItemTrait, Lit,
    PathArguments, ReturnType, TraitItem, Type,
};

/// Rust source input for code-first Protobuf schema generation.
pub struct RustSourceConfig {
    /// Rust file containing one annotated inline contract module.
    pub source: PathBuf,
    /// Standard Protobuf schema written by the generator.
    pub output: PathBuf,
    /// Optional compatibility lock used to retain numbers and reserve removals.
    pub schema_lock: Option<PathBuf>,
}

#[derive(Default)]
struct LockedSchema {
    messages: BTreeMap<String, LockedMessage>,
}

#[derive(Default)]
struct LockedMessage {
    fields: BTreeMap<i32, String>,
    reserved_numbers: BTreeSet<i32>,
    reserved_names: BTreeSet<String>,
}

#[derive(Default)]
struct RpcOptions {
    server_streaming: bool,
    values: BTreeMap<String, String>,
    scopes: Vec<String>,
}

/// Generates a deterministic standard Protobuf schema from annotated Rust declarations.
pub fn generate_proto_from_rust_source(config: RustSourceConfig) -> io::Result<()> {
    let source = fs::read_to_string(&config.source)?;
    let syntax = syn::parse_file(&source).map_err(invalid)?;
    let contract = syntax
        .items
        .iter()
        .find_map(|item| match item {
            Item::Mod(module) if attribute(&module.attrs, "contract").is_some() => Some(module),
            _ => None,
        })
        .ok_or_else(|| invalid("expected one #[moonlight::contract(...)] inline module"))?;
    let (package, java_package) = contract_names(contract)?;
    let locked = config
        .schema_lock
        .as_ref()
        .filter(|path| path.is_file())
        .map(load_lock)
        .transpose()?
        .unwrap_or_default();
    let items = contract
        .content
        .as_ref()
        .map(|(_, items)| items)
        .ok_or_else(|| invalid("the contract module must be declared inline"))?;
    let has_policy = items.iter().any(|item| match item {
        Item::Trait(service) if attribute(&service.attrs, "service").is_some() => service
            .items
            .iter()
            .any(|item| matches!(item, TraitItem::Fn(method) if attribute(&method.attrs, "rpc").is_some())),
        _ => false,
    });

    let mut proto = String::from("syntax = \"proto3\";\n\n");
    writeln!(proto, "package {package};\n").unwrap();
    if has_policy {
        proto.push_str("import \"moonlight/bridge/options/v1/options.proto\";\n\n");
    }
    writeln!(proto, "option java_package = \"{java_package}\";").unwrap();
    proto.push_str("option java_multiple_files = true;\n\n");

    for item in items {
        match item {
            Item::Struct(message) if attribute(&message.attrs, "message").is_some() => {
                generate_message(&mut proto, &package, message, &locked)?;
            }
            Item::Enum(enumeration) if attribute(&enumeration.attrs, "enumeration").is_some() => {
                generate_enum(&mut proto, enumeration)?;
            }
            Item::Trait(service) if attribute(&service.attrs, "service").is_some() => {
                generate_service(&mut proto, service)?;
            }
            _ => {}
        }
    }
    if let Some(parent) = config.output.parent() {
        fs::create_dir_all(parent)?;
    }
    fs::write(config.output, proto)
}

fn contract_names(module: &ItemMod) -> io::Result<(String, String)> {
    let attr = attribute(&module.attrs, "contract").unwrap();
    let mut package = None;
    let mut java_package = None;
    attr.parse_nested_meta(|meta| {
        let value = meta.value()?.parse::<syn::LitStr>()?.value();
        if meta.path.is_ident("package") {
            package = Some(value);
        } else if meta.path.is_ident("java_package") {
            java_package = Some(value);
        } else {
            return Err(meta.error("supported keys are package and java_package"));
        }
        Ok(())
    })
    .map_err(invalid)?;
    let package = package.ok_or_else(|| invalid("contract package is required"))?;
    let java_package = java_package.unwrap_or_else(|| package.clone());
    Ok((package, java_package))
}

fn generate_message(
    proto: &mut String,
    package: &str,
    message: &ItemStruct,
    locked: &LockedSchema,
) -> io::Result<()> {
    let name = message.ident.to_string();
    let full_name = format!("{package}.{name}");
    let previous = locked.messages.get(&full_name);
    let Fields::Named(fields) = &message.fields else {
        return Err(invalid(format!("message {name} must use named fields")));
    };
    let current_names = fields
        .named
        .iter()
        .filter_map(|field| field.ident.as_ref().map(ToString::to_string))
        .collect::<BTreeSet<_>>();
    let mut used = previous
        .map(|message| message.fields.keys().copied().collect::<BTreeSet<_>>())
        .unwrap_or_default();
    if let Some(previous) = previous {
        used.extend(previous.reserved_numbers.iter().copied());
    }

    writeln!(proto, "message {name} {{").unwrap();
    if let Some(previous) = previous {
        for (number, old_name) in &previous.fields {
            if !current_names.contains(old_name) {
                writeln!(proto, "  reserved {number};").unwrap();
                writeln!(proto, "  reserved \"{old_name}\";").unwrap();
            }
        }
        for number in &previous.reserved_numbers {
            writeln!(proto, "  reserved {number};").unwrap();
        }
        for name in &previous.reserved_names {
            writeln!(proto, "  reserved \"{name}\";").unwrap();
        }
    }
    for field in &fields.named {
        let field_name = field.ident.as_ref().unwrap().to_string();
        let number = previous
            .and_then(|message| {
                message
                    .fields
                    .iter()
                    .find_map(|(number, name)| (name == &field_name).then_some(*number))
            })
            .unwrap_or_else(|| next_number(&mut used));
        used.insert(number);
        let (label, field_type) = proto_type(&field.ty).map_err(|error| {
            invalid(format!(
                "unsupported Rust type for {name}.{field_name}: {error}"
            ))
        })?;
        writeln!(proto, "  {label}{field_type} {field_name} = {number};").unwrap();
    }
    proto.push_str("}\n\n");
    Ok(())
}

fn generate_enum(proto: &mut String, enumeration: &syn::ItemEnum) -> io::Result<()> {
    let name = enumeration.ident.to_string();
    writeln!(proto, "enum {name} {{").unwrap();
    for (number, variant) in enumeration.variants.iter().enumerate() {
        if !matches!(variant.fields, Fields::Unit) {
            return Err(invalid(format!(
                "enum {name} variants must not contain fields"
            )));
        }
        writeln!(
            proto,
            "  {} = {number};",
            upper_snake(&variant.ident.to_string())
        )
        .unwrap();
    }
    proto.push_str("}\n\n");
    Ok(())
}

fn generate_service(proto: &mut String, service: &ItemTrait) -> io::Result<()> {
    let name = service.ident.to_string();
    writeln!(proto, "service {name} {{").unwrap();
    for item in &service.items {
        let TraitItem::Fn(method) = item else {
            continue;
        };
        let method_name = upper_camel(&method.sig.ident.to_string());
        let input = method
            .sig
            .inputs
            .iter()
            .find_map(|argument| match argument {
                FnArg::Typed(argument) => simple_type_name(&argument.ty),
                FnArg::Receiver(_) => None,
            })
            .ok_or_else(|| invalid(format!("RPC {name}.{method_name} needs one request type")))?;
        let output = match &method.sig.output {
            ReturnType::Type(_, ty) => simple_type_name(ty),
            ReturnType::Default => None,
        }
        .ok_or_else(|| invalid(format!("RPC {name}.{method_name} needs one response type")))?;
        let options = rpc_options(&method.attrs)?;
        let stream = if options.server_streaming {
            "stream "
        } else {
            ""
        };
        if options.values.is_empty() && options.scopes.is_empty() {
            writeln!(
                proto,
                "  rpc {method_name}({input}) returns ({stream}{output});"
            )
            .unwrap();
        } else {
            writeln!(
                proto,
                "  rpc {method_name}({input}) returns ({stream}{output}) {{"
            )
            .unwrap();
            proto.push_str("    option (moonlight.bridge.options.v1.rpc_policy) = {\n");
            for (key, value) in &options.values {
                if is_retry_key(key) {
                    continue;
                }
                let rendered = render_policy_value(key, value)?;
                writeln!(proto, "      {key}: {rendered}").unwrap();
            }
            let retry = options
                .values
                .iter()
                .filter(|(key, _)| is_retry_key(key))
                .collect::<Vec<_>>();
            if !retry.is_empty() {
                proto.push_str("      retry: {\n");
                for (key, value) in retry {
                    writeln!(proto, "        {key}: {value}").unwrap();
                }
                proto.push_str("      }\n");
            }
            for scope in &options.scopes {
                writeln!(proto, "      required_scopes: \"{scope}\"").unwrap();
            }
            proto.push_str("    };\n  }\n");
        }
    }
    proto.push_str("}\n\n");
    Ok(())
}

fn rpc_options(attributes: &[Attribute]) -> io::Result<RpcOptions> {
    let Some(attr) = attribute(attributes, "rpc") else {
        return Ok(RpcOptions::default());
    };
    let mut options = RpcOptions::default();
    attr.parse_nested_meta(|meta| {
        let key = meta
            .path
            .get_ident()
            .ok_or_else(|| meta.error("expected option name"))?
            .to_string();
        if key == "server_streaming" {
            options.server_streaming = true;
            return Ok(());
        }
        let literal = meta.value()?.parse::<Lit>()?;
        let value = match literal {
            Lit::Str(value) => value.value(),
            Lit::Int(value) => value.base10_digits().to_owned(),
            _ => return Err(meta.error("expected string or integer value")),
        };
        if key == "required_scope" {
            options.scopes.push(value);
        } else {
            options.values.insert(key, value);
        }
        Ok(())
    })
    .map_err(invalid)?;
    Ok(options)
}

fn render_policy_value(key: &str, value: &str) -> io::Result<String> {
    match key {
        "idempotency" => Ok(format!("IDEMPOTENCY_{}", upper_snake(value))),
        "compression" => Ok(format!("COMPRESSION_MODE_{}", upper_snake(value))),
        "timeout_ms"
        | "idle_timeout_ms"
        | "max_request_bytes"
        | "max_response_bytes"
        | "trace_sample_per_million" => Ok(value.to_owned()),
        _ => Err(invalid(format!("unknown RPC policy option {key}"))),
    }
}

fn is_retry_key(key: &str) -> bool {
    matches!(
        key,
        "max_attempts" | "initial_backoff_ms" | "max_backoff_ms" | "multiplier_milli"
    )
}

fn proto_type(ty: &Type) -> io::Result<(&'static str, String)> {
    let Type::Path(path) = ty else {
        return Err(invalid("expected a path type"));
    };
    let segment = path
        .path
        .segments
        .last()
        .ok_or_else(|| invalid("empty type path"))?;
    let name = segment.ident.to_string();
    if name == "Vec" || name == "Option" {
        let PathArguments::AngleBracketed(arguments) = &segment.arguments else {
            return Err(invalid(format!("{name} needs one type argument")));
        };
        let inner = arguments
            .args
            .iter()
            .find_map(|argument| match argument {
                GenericArgument::Type(ty) => Some(ty),
                _ => None,
            })
            .ok_or_else(|| invalid(format!("{name} needs one type argument")))?;
        if name == "Vec" && simple_type_name(inner).as_deref() == Some("u8") {
            return Ok(("", "bytes".to_owned()));
        }
        let (_, inner) = proto_type(inner)?;
        return Ok((
            if name == "Vec" {
                "repeated "
            } else {
                "optional "
            },
            inner,
        ));
    }
    let mapped = match name.as_str() {
        "String" | "str" => "string",
        "bool" => "bool",
        "i32" => "int32",
        "i64" => "int64",
        "u32" => "uint32",
        "u64" => "uint64",
        "f32" => "float",
        "f64" => "double",
        other if path.path.segments.len() == 1 => other,
        _ => return Err(invalid(format!("qualified type {name}"))),
    };
    Ok(("", mapped.to_owned()))
}

fn simple_type_name(ty: &Type) -> Option<String> {
    let Type::Path(path) = ty else { return None };
    (path.path.segments.len() == 1).then(|| path.path.segments[0].ident.to_string())
}

fn load_lock(path: &PathBuf) -> io::Result<LockedSchema> {
    let value: Value = serde_json::from_slice(&fs::read(path)?).map_err(invalid)?;
    let mut schema = LockedSchema::default();
    let Some(messages) = value.get("messages").and_then(Value::as_object) else {
        return Ok(schema);
    };
    for (full_name, message) in messages {
        let mut locked = LockedMessage::default();
        if let Some(fields) = message.get("fields").and_then(Value::as_object) {
            for (number, field) in fields {
                let number = number.parse::<i32>().map_err(invalid)?;
                if let Some(name) = field.get("name").and_then(Value::as_str) {
                    locked.fields.insert(number, name.to_owned());
                }
            }
        }
        if let Some(ranges) = message.get("reserved_ranges").and_then(Value::as_array) {
            for range in ranges {
                let start = range
                    .get("start")
                    .and_then(Value::as_i64)
                    .unwrap_or_default() as i32;
                let end = range.get("end").and_then(Value::as_i64).unwrap_or_default() as i32;
                locked.reserved_numbers.extend(start..end);
            }
        }
        if let Some(names) = message.get("reserved_names").and_then(Value::as_array) {
            locked
                .reserved_names
                .extend(names.iter().filter_map(Value::as_str).map(str::to_owned));
        }
        schema.messages.insert(full_name.clone(), locked);
    }
    Ok(schema)
}

fn next_number(used: &mut BTreeSet<i32>) -> i32 {
    let mut candidate = 1;
    while used.contains(&candidate) || (19_000..=19_999).contains(&candidate) {
        candidate += 1;
    }
    candidate
}

fn attribute<'a>(attributes: &'a [Attribute], name: &str) -> Option<&'a Attribute> {
    attributes.iter().find(|attribute| {
        attribute
            .path()
            .segments
            .last()
            .is_some_and(|segment| segment.ident == name)
    })
}

fn upper_camel(value: &str) -> String {
    value
        .split('_')
        .filter(|part| !part.is_empty())
        .map(|part| {
            let mut chars = part.chars();
            chars
                .next()
                .map(|first| first.to_uppercase().collect::<String>() + chars.as_str())
                .unwrap_or_default()
        })
        .collect()
}

fn upper_snake(value: &str) -> String {
    let mut output = String::new();
    for (index, character) in value.chars().enumerate() {
        if character.is_ascii_uppercase() && index > 0 {
            output.push('_');
        }
        output.push(character.to_ascii_uppercase());
    }
    output
}

fn invalid(error: impl std::fmt::Display) -> io::Error {
    io::Error::new(io::ErrorKind::InvalidData, error.to_string())
}
