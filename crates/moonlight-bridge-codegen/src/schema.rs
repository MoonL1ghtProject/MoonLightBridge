use prost::Message as _;
use prost_types::{DescriptorProto, EnumDescriptorProto, FileDescriptorProto, FileDescriptorSet};
use serde::{Deserialize, Serialize};
use std::{collections::BTreeMap, fs, io, path::Path};

const LOCK_FORMAT: u32 = 1;

#[derive(Clone, Debug, Serialize, Deserialize, PartialEq, Eq)]
struct SchemaLock {
    format: u32,
    messages: BTreeMap<String, LockedMessage>,
    enums: BTreeMap<String, LockedEnum>,
    services: BTreeMap<String, LockedService>,
}

#[derive(Clone, Debug, Serialize, Deserialize, PartialEq, Eq)]
struct LockedMessage {
    fields: BTreeMap<i32, LockedField>,
    reserved_ranges: Vec<NumberRange>,
    reserved_names: Vec<String>,
}

#[derive(Clone, Debug, Serialize, Deserialize, PartialEq, Eq)]
struct LockedField {
    name: String,
    number: i32,
    label: i32,
    kind: i32,
    type_name: String,
    oneof_index: Option<i32>,
    proto3_optional: bool,
}

#[derive(Clone, Debug, Serialize, Deserialize, PartialEq, Eq)]
struct NumberRange {
    start: i32,
    end: i32,
}

#[derive(Clone, Debug, Serialize, Deserialize, PartialEq, Eq)]
struct LockedEnum {
    values: BTreeMap<i32, String>,
    reserved_ranges: Vec<NumberRange>,
    reserved_names: Vec<String>,
}

#[derive(Clone, Debug, Serialize, Deserialize, PartialEq, Eq)]
struct LockedService {
    methods: BTreeMap<String, LockedMethod>,
}

#[derive(Clone, Debug, Serialize, Deserialize, PartialEq, Eq)]
struct LockedMethod {
    input: String,
    output: String,
    client_streaming: bool,
    server_streaming: bool,
}

pub fn write_lock(
    descriptor_path: impl AsRef<Path>,
    lock_path: impl AsRef<Path>,
) -> io::Result<()> {
    let snapshot = snapshot(descriptor_path)?;
    let json = serde_json::to_string_pretty(&snapshot).map_err(invalid)?;
    fs::write(lock_path, format!("{json}\n"))
}

pub fn check_lock(
    descriptor_path: impl AsRef<Path>,
    lock_path: impl AsRef<Path>,
) -> io::Result<()> {
    let current = snapshot(descriptor_path)?;
    let lock_bytes = fs::read(lock_path.as_ref()).map_err(|error| {
        if error.kind() == io::ErrorKind::NotFound {
            invalid(format!(
                "schema lock is missing at {}; run moonlight-bridge-codegen lock <descriptor> <lock> --update",
                lock_path.as_ref().display()
            ))
        } else {
            error
        }
    })?;
    let previous: SchemaLock = serde_json::from_slice(&lock_bytes).map_err(invalid)?;
    if previous.format != LOCK_FORMAT {
        return Err(invalid(format!(
            "unsupported schema lock format {}",
            previous.format
        )));
    }
    let errors = compatibility_errors(&previous, &current);
    if errors.is_empty() {
        return Ok(());
    }
    Err(invalid(format!(
        "breaking schema changes:\n- {}",
        errors.join("\n- ")
    )))
}

fn snapshot(path: impl AsRef<Path>) -> io::Result<SchemaLock> {
    let descriptor = FileDescriptorSet::decode(fs::read(path)?.as_slice()).map_err(invalid)?;
    let mut lock = SchemaLock {
        format: LOCK_FORMAT,
        messages: BTreeMap::new(),
        enums: BTreeMap::new(),
        services: BTreeMap::new(),
    };
    for file in &descriptor.file {
        snapshot_file(file, &mut lock)?;
    }
    Ok(lock)
}

fn snapshot_file(file: &FileDescriptorProto, lock: &mut SchemaLock) -> io::Result<()> {
    let package = file.package.as_deref().unwrap_or("");
    for message in &file.message_type {
        snapshot_message(package, message, lock)?;
    }
    for enumeration in &file.enum_type {
        snapshot_enum(package, enumeration, lock)?;
    }
    for service in &file.service {
        let name = required(&service.name, "service name")?;
        let full_name = qualify(package, name);
        let mut methods = BTreeMap::new();
        for method in &service.method {
            let method_name = required(&method.name, "method name")?.to_owned();
            methods.insert(
                method_name,
                LockedMethod {
                    input: required(&method.input_type, "method input")?.to_owned(),
                    output: required(&method.output_type, "method output")?.to_owned(),
                    client_streaming: method.client_streaming.unwrap_or(false),
                    server_streaming: method.server_streaming.unwrap_or(false),
                },
            );
        }
        lock.services.insert(full_name, LockedService { methods });
    }
    Ok(())
}

fn snapshot_message(
    prefix: &str,
    message: &DescriptorProto,
    lock: &mut SchemaLock,
) -> io::Result<()> {
    let name = required(&message.name, "message name")?;
    let full_name = qualify(prefix, name);
    let fields = message
        .field
        .iter()
        .map(|field| {
            let number = field
                .number
                .ok_or_else(|| invalid("field number is missing"))?;
            Ok((
                number,
                LockedField {
                    name: required(&field.name, "field name")?.to_owned(),
                    number,
                    label: field.label.unwrap_or_default(),
                    kind: field.r#type.unwrap_or_default(),
                    type_name: field.type_name.clone().unwrap_or_default(),
                    oneof_index: field.oneof_index,
                    proto3_optional: field.proto3_optional.unwrap_or(false),
                },
            ))
        })
        .collect::<io::Result<BTreeMap<_, _>>>()?;
    let reserved_ranges = message
        .reserved_range
        .iter()
        .map(|range| NumberRange {
            start: range.start.unwrap_or_default(),
            end: range.end.unwrap_or_default(),
        })
        .collect();
    lock.messages.insert(
        full_name.clone(),
        LockedMessage {
            fields,
            reserved_ranges,
            reserved_names: message.reserved_name.clone(),
        },
    );
    for nested in &message.nested_type {
        snapshot_message(&full_name, nested, lock)?;
    }
    for enumeration in &message.enum_type {
        snapshot_enum(&full_name, enumeration, lock)?;
    }
    Ok(())
}

fn snapshot_enum(
    prefix: &str,
    enumeration: &EnumDescriptorProto,
    lock: &mut SchemaLock,
) -> io::Result<()> {
    let name = required(&enumeration.name, "enum name")?;
    let values = enumeration
        .value
        .iter()
        .map(|value| {
            Ok((
                value
                    .number
                    .ok_or_else(|| invalid("enum value number is missing"))?,
                required(&value.name, "enum value name")?.to_owned(),
            ))
        })
        .collect::<io::Result<BTreeMap<_, _>>>()?;
    let reserved_ranges = enumeration
        .reserved_range
        .iter()
        .map(|range| NumberRange {
            start: range.start.unwrap_or_default(),
            // Enum reserved ranges are inclusive in descriptor.proto.
            end: range.end.unwrap_or_default().saturating_add(1),
        })
        .collect();
    lock.enums.insert(
        qualify(prefix, name),
        LockedEnum {
            values,
            reserved_ranges,
            reserved_names: enumeration.reserved_name.clone(),
        },
    );
    Ok(())
}

fn compatibility_errors(old: &SchemaLock, new: &SchemaLock) -> Vec<String> {
    let mut errors = Vec::new();
    for (name, old_message) in &old.messages {
        let Some(new_message) = new.messages.get(name) else {
            errors.push(format!("message {name} was removed"));
            continue;
        };
        for (number, old_field) in &old_message.fields {
            match new_message.fields.get(number) {
                Some(new_field) if new_field != old_field => errors.push(format!(
                    "field {name}.{} #{number} changed from {:?} to {:?}", old_field.name, old_field, new_field
                )),
                Some(_) => {}
                None if !is_reserved(*number, &old_field.name, &new_message.reserved_ranges, &new_message.reserved_names) => errors.push(format!(
                    "field {name}.{} #{number} was removed without reserving both its number and name", old_field.name
                )),
                None => {}
            }
        }
    }
    for (name, old_enum) in &old.enums {
        let Some(new_enum) = new.enums.get(name) else {
            errors.push(format!("enum {name} was removed"));
            continue;
        };
        for (number, old_name) in &old_enum.values {
            match new_enum.values.get(number) {
                Some(new_name) if new_name != old_name => errors.push(format!("enum value {name}.{old_name} #{number} changed to {new_name}")),
                Some(_) => {}
                None if !is_reserved(*number, old_name, &new_enum.reserved_ranges, &new_enum.reserved_names) => errors.push(format!(
                    "enum value {name}.{old_name} #{number} was removed without reserving both its number and name"
                )),
                None => {}
            }
        }
    }
    for (name, old_service) in &old.services {
        let Some(new_service) = new.services.get(name) else {
            errors.push(format!("service {name} was removed"));
            continue;
        };
        for (method, old_method) in &old_service.methods {
            match new_service.methods.get(method) {
                None => errors.push(format!("RPC {name}/{method} was removed or renamed")),
                Some(new_method) if new_method != old_method => {
                    errors.push(format!("RPC {name}/{method} signature changed"))
                }
                Some(_) => {}
            }
        }
    }
    errors
}

fn is_reserved(number: i32, name: &str, ranges: &[NumberRange], names: &[String]) -> bool {
    ranges
        .iter()
        .any(|range| number >= range.start && number < range.end)
        && names.iter().any(|reserved| reserved == name)
}

fn qualify(prefix: &str, name: &str) -> String {
    if prefix.is_empty() {
        name.to_owned()
    } else {
        format!("{prefix}.{name}")
    }
}

fn required<'a>(value: &'a Option<String>, field: &str) -> io::Result<&'a str> {
    value
        .as_deref()
        .ok_or_else(|| invalid(format!("missing {field}")))
}

fn invalid(error: impl std::fmt::Display) -> io::Error {
    io::Error::new(io::ErrorKind::InvalidData, error.to_string())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn additive_field_is_compatible() {
        let old = example_lock();
        let mut new = old.clone();
        new.messages.get_mut("test.Player").unwrap().fields.insert(
            2,
            LockedField {
                name: "level".to_owned(),
                number: 2,
                label: 1,
                kind: 5,
                type_name: String::new(),
                oneof_index: None,
                proto3_optional: false,
            },
        );
        assert!(compatibility_errors(&old, &new).is_empty());
    }

    #[test]
    fn changed_field_type_is_breaking() {
        let old = example_lock();
        let mut new = old.clone();
        new.messages
            .get_mut("test.Player")
            .unwrap()
            .fields
            .get_mut(&1)
            .unwrap()
            .kind = 3;
        assert!(compatibility_errors(&old, &new)[0].contains("changed"));
    }

    #[test]
    fn removed_field_requires_reserved_name_and_number() {
        let old = example_lock();
        let mut invalid = old.clone();
        invalid
            .messages
            .get_mut("test.Player")
            .unwrap()
            .fields
            .remove(&1);
        assert_eq!(compatibility_errors(&old, &invalid).len(), 1);

        let message = invalid.messages.get_mut("test.Player").unwrap();
        message
            .reserved_ranges
            .push(NumberRange { start: 1, end: 2 });
        message.reserved_names.push("name".to_owned());
        assert!(compatibility_errors(&old, &invalid).is_empty());
    }

    fn example_lock() -> SchemaLock {
        let field = LockedField {
            name: "name".to_owned(),
            number: 1,
            label: 1,
            kind: 9,
            type_name: String::new(),
            oneof_index: None,
            proto3_optional: false,
        };
        SchemaLock {
            format: LOCK_FORMAT,
            messages: BTreeMap::from([(
                "test.Player".to_owned(),
                LockedMessage {
                    fields: BTreeMap::from([(1, field)]),
                    reserved_ranges: Vec::new(),
                    reserved_names: Vec::new(),
                },
            )]),
            enums: BTreeMap::new(),
            services: BTreeMap::new(),
        }
    }
}
