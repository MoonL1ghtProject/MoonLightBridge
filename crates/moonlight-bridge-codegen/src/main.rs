use std::{env, io, path::PathBuf};

fn main() -> io::Result<()> {
    let arguments = env::args_os()
        .skip(1)
        .map(PathBuf::from)
        .collect::<Vec<_>>();
    match arguments.as_slice() {
        [command, language, source, output] if command == "source" && language == "rust" => {
            moonlight_bridge_codegen::generate_proto_from_rust_source(
                moonlight_bridge_codegen::RustSourceConfig {
                    source: source.clone(),
                    output: output.clone(),
                    schema_lock: None,
                },
            )
        }
        [command, language, source, output, lock] if command == "source" && language == "rust" => {
            moonlight_bridge_codegen::generate_proto_from_rust_source(
                moonlight_bridge_codegen::RustSourceConfig {
                    source: source.clone(),
                    output: output.clone(),
                    schema_lock: Some(lock.clone()),
                },
            )
        }
        [descriptor, java_output] => {
            moonlight_bridge_codegen::generate_java(descriptor, java_output)
        }
        [command, descriptor, lock, mode] if command == "lock" && mode == "--update" => {
            moonlight_bridge_codegen::schema::write_lock(descriptor, lock)
        }
        [command, descriptor, lock, mode] if command == "lock" && mode == "--check" => {
            moonlight_bridge_codegen::schema::check_lock(descriptor, lock)
        }
        _ => Err(usage()),
    }
}

fn usage() -> io::Error {
    io::Error::new(
        io::ErrorKind::InvalidInput,
        "usage:\n  moonlight-bridge-codegen source rust <contract.rs> <output.proto> [schema.lock]\n  moonlight-bridge-codegen <descriptor.pb> <java-output>\n  moonlight-bridge-codegen lock <descriptor.pb> <schema.lock> --check|--update",
    )
}
