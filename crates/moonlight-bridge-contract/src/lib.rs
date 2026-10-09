#![doc = "Rust attributes understood by MoonLightBridge code-first generation."]

use proc_macro::TokenStream;

/// Marks an inline module as a MoonLightBridge contract.
///
/// The build-time generator reads `package` and `java_package`. The macro keeps
/// the module available to rust-analyzer and rustc without adding runtime work.
#[proc_macro_attribute]
pub fn contract(_attributes: TokenStream, item: TokenStream) -> TokenStream {
    let mut output: TokenStream = "#[allow(dead_code)]"
        .parse()
        .expect("static allow attribute must parse");
    output.extend(item);
    output
}

/// Marks a named-field struct as a Protobuf message.
#[proc_macro_attribute]
pub fn message(_attributes: TokenStream, item: TokenStream) -> TokenStream {
    item
}

/// Marks a fieldless Rust enum as a Protobuf enum.
#[proc_macro_attribute]
pub fn enumeration(_attributes: TokenStream, item: TokenStream) -> TokenStream {
    item
}

/// Marks a trait as a generated MoonLightBridge service contract.
#[proc_macro_attribute]
pub fn service(_attributes: TokenStream, item: TokenStream) -> TokenStream {
    item
}

/// Attaches streaming and protocol-v2 policy settings to a service method.
#[proc_macro_attribute]
pub fn rpc(_attributes: TokenStream, item: TokenStream) -> TokenStream {
    item
}
