use std::{
    io::Read,
    process::{Command, Stdio},
    thread,
    time::Duration,
};

#[test]
fn local_example_backend_starts_with_scoped_routes() {
    let mut child = Command::new(env!("CARGO_BIN_EXE_moonlight-bridge-test-plugin-backend"))
        .env("MOONLIGHT_BRIDGE_ADDRESS", "127.0.0.1:0")
        .stdout(Stdio::piped())
        .stderr(Stdio::piped())
        .spawn()
        .expect("start test-plugin backend");

    thread::sleep(Duration::from_millis(500));
    if let Some(status) = child.try_wait().expect("poll test-plugin backend") {
        let mut stderr = String::new();
        child
            .stderr
            .take()
            .expect("captured stderr")
            .read_to_string(&mut stderr)
            .expect("read backend stderr");
        panic!("test-plugin backend exited with {status}: {stderr}");
    }

    child.kill().expect("stop test-plugin backend");
    child.wait().expect("reap test-plugin backend");
}
