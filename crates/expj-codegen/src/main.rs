use std::{env, io, path::PathBuf};

fn main() -> io::Result<()> {
    let arguments = env::args_os()
        .skip(1)
        .map(PathBuf::from)
        .collect::<Vec<_>>();
    match arguments.as_slice() {
        [descriptor, java_output] => expj_codegen::generate_java(descriptor, java_output),
        [command, descriptor, lock, mode] if command == "lock" && mode == "--update" => {
            expj_codegen::schema::write_lock(descriptor, lock)
        }
        [command, descriptor, lock, mode] if command == "lock" && mode == "--check" => {
            expj_codegen::schema::check_lock(descriptor, lock)
        }
        _ => Err(usage()),
    }
}

fn usage() -> io::Error {
    io::Error::new(
        io::ErrorKind::InvalidInput,
        "usage:\n  expj-codegen <descriptor.pb> <java-output>\n  expj-codegen lock <descriptor.pb> <schema.lock> --check|--update",
    )
}
