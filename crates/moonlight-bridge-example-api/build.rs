fn main() -> Result<(), Box<dyn std::error::Error>> {
    println!("cargo:rerun-if-changed=../../proto/moonlight/bridge/example/v1/echo.proto");
    println!("cargo:rerun-if-changed=../../proto/schema.lock");
    let output = std::path::PathBuf::from(std::env::var("OUT_DIR")?);
    let descriptor = output.join("descriptor.pb");
    let mut config = prost_build::Config::new();
    config.file_descriptor_set_path(&descriptor);
    config.compile_protos(
        &["../../proto/moonlight/bridge/example/v1/echo.proto"],
        &["../../proto"],
    )?;
    moonlight_bridge_codegen::schema::check_lock(&descriptor, "../../proto/schema.lock")?;
    moonlight_bridge_codegen::generate_rust(
        descriptor,
        output.join("moonlight_bridge_services.rs"),
    )?;
    Ok(())
}
