use prost_reflect::{DescriptorPool, DynamicMessage, Value};
use serde::{Deserialize, Serialize};
use std::{collections::BTreeMap, fs, io, path::Path};

const POLICY_EXTENSION: &str = "moonlight.bridge.options.v1.rpc_policy";
const DEFAULT_TIMEOUT_MS: u32 = 2_000;
const DEFAULT_STREAM_IDLE_TIMEOUT_MS: u32 = 30_000;
const DEFAULT_MAX_BODY_BYTES: u32 = 16 * 1024 * 1024;

/// Retry safety classification declared by an RPC schema.
#[derive(Clone, Copy, Debug, Default, Deserialize, Eq, PartialEq, Serialize)]
pub enum Idempotency {
    /// The method has no retry-safety declaration.
    #[default]
    Unspecified,
    /// The method has no externally visible mutation.
    ReadOnly,
    /// Repeating the same request has the same externally visible effect.
    Idempotent,
    /// Retries require one stable idempotency key across every attempt.
    KeyRequired,
}

/// Per-method compression preference compiled into generated bindings.
#[derive(Clone, Copy, Debug, Default, Deserialize, Eq, PartialEq, Serialize)]
pub enum CompressionMode {
    /// Use the runtime's negotiated default.
    #[default]
    Default,
    /// Never compress this method's application payloads.
    Disabled,
    /// Compress when negotiation, thresholds, and savings permit it.
    Prefer,
    /// Fail the call when a compressed payload cannot be negotiated.
    Required,
}

/// Validated bounded retry policy for one RPC method.
#[derive(Clone, Copy, Debug, Deserialize, Eq, PartialEq, Serialize)]
pub struct RetryPolicy {
    /// Total attempts including the initial attempt.
    pub max_attempts: u32,
    /// Delay before the first retry.
    pub initial_backoff_ms: u32,
    /// Maximum delay between attempts.
    pub max_backoff_ms: u32,
    /// Backoff multiplier in thousandths; `2000` means 2x.
    pub multiplier_milli: u32,
}

impl Default for RetryPolicy {
    fn default() -> Self {
        Self {
            max_attempts: 1,
            initial_backoff_ms: 25,
            max_backoff_ms: 1_000,
            multiplier_milli: 2_000,
        }
    }
}

/// Normalized RPC policy obtained from a Protobuf method option.
#[derive(Clone, Debug, Deserialize, Eq, PartialEq, Serialize)]
pub struct RpcPolicy {
    /// Overall logical-call timeout.
    pub timeout_ms: u32,
    /// Maximum silence between stream items; zero for unary calls.
    pub idle_timeout_ms: u32,
    /// Bounded retry settings.
    pub retry: RetryPolicy,
    /// Retry-safety classification.
    pub idempotency: Idempotency,
    /// Maximum decoded request bytes.
    pub max_request_bytes: u32,
    /// Maximum decoded response or stream-item bytes.
    pub max_response_bytes: u32,
    /// Authorization scopes required by generated middleware hooks.
    pub required_scopes: Vec<String>,
    /// Per-method compression behavior.
    pub compression: CompressionMode,
    /// Trace sampling probability in millionths.
    pub trace_sample_per_million: u32,
}

impl RpcPolicy {
    pub(crate) fn defaults(server_streaming: bool) -> Self {
        Self {
            timeout_ms: DEFAULT_TIMEOUT_MS,
            idle_timeout_ms: if server_streaming {
                DEFAULT_STREAM_IDLE_TIMEOUT_MS
            } else {
                0
            },
            retry: RetryPolicy::default(),
            idempotency: Idempotency::Unspecified,
            max_request_bytes: DEFAULT_MAX_BODY_BYTES,
            max_response_bytes: DEFAULT_MAX_BODY_BYTES,
            required_scopes: Vec::new(),
            compression: CompressionMode::Default,
            trace_sample_per_million: 0,
        }
    }
}

/// Reads, normalizes, and validates every RPC policy in a descriptor set.
pub fn descriptor_policies(
    descriptor_path: impl AsRef<Path>,
) -> io::Result<BTreeMap<String, RpcPolicy>> {
    let bytes = fs::read(descriptor_path)?;
    let pool = DescriptorPool::decode(bytes.as_slice()).map_err(invalid)?;
    policies_from_pool(&pool)
}

pub(crate) fn policies_from_pool(pool: &DescriptorPool) -> io::Result<BTreeMap<String, RpcPolicy>> {
    let extension = pool.get_extension_by_name(POLICY_EXTENSION);
    let mut policies = BTreeMap::new();
    for service in pool.services() {
        for method in service.methods() {
            let canonical = format!("{}/{}", service.full_name(), method.name());
            let mut policy = RpcPolicy::defaults(method.is_server_streaming());
            if let Some(extension) = extension.as_ref() {
                let options = method.options();
                if options.has_extension(extension) {
                    let value = options.get_extension(extension);
                    let Value::Message(message) = value.as_ref() else {
                        return Err(invalid(format!(
                            "RPC {canonical} has a non-message rpc_policy option"
                        )));
                    };
                    policy = parse_policy(message, method.is_server_streaming())?;
                }
            }
            validate_policy(
                &canonical,
                &policy,
                method.is_client_streaming() || method.is_server_streaming(),
                method.is_server_streaming(),
            )?;
            policies.insert(canonical, policy);
        }
    }
    Ok(policies)
}

fn parse_policy(message: &DynamicMessage, server_streaming: bool) -> io::Result<RpcPolicy> {
    let defaults = RpcPolicy::defaults(server_streaming);
    let mut required_scopes = string_list(message, "required_scopes")?;
    required_scopes.sort();
    if required_scopes.windows(2).any(|pair| pair[0] == pair[1]) {
        return Err(invalid("duplicate required scope"));
    }
    Ok(RpcPolicy {
        timeout_ms: optional_u32(message, "timeout_ms", defaults.timeout_ms)?,
        idle_timeout_ms: optional_u32(message, "idle_timeout_ms", defaults.idle_timeout_ms)?,
        retry: message_field(message, "retry")?
            .map(parse_retry)
            .transpose()?
            .unwrap_or_default(),
        idempotency: match enum_number(message, "idempotency")? {
            0 => Idempotency::Unspecified,
            1 => Idempotency::ReadOnly,
            2 => Idempotency::Idempotent,
            3 => Idempotency::KeyRequired,
            other => return Err(invalid(format!("unknown idempotency value {other}"))),
        },
        max_request_bytes: optional_u32(message, "max_request_bytes", defaults.max_request_bytes)?,
        max_response_bytes: optional_u32(
            message,
            "max_response_bytes",
            defaults.max_response_bytes,
        )?,
        required_scopes,
        compression: match enum_number(message, "compression")? {
            0 => CompressionMode::Default,
            1 => CompressionMode::Disabled,
            2 => CompressionMode::Prefer,
            3 => CompressionMode::Required,
            other => return Err(invalid(format!("unknown compression value {other}"))),
        },
        trace_sample_per_million: optional_u32(message, "trace_sample_per_million", 0)?,
    })
}

fn parse_retry(message: &DynamicMessage) -> io::Result<RetryPolicy> {
    let defaults = RetryPolicy::default();
    Ok(RetryPolicy {
        max_attempts: optional_u32(message, "max_attempts", defaults.max_attempts)?,
        initial_backoff_ms: optional_u32(
            message,
            "initial_backoff_ms",
            defaults.initial_backoff_ms,
        )?,
        max_backoff_ms: optional_u32(message, "max_backoff_ms", defaults.max_backoff_ms)?,
        multiplier_milli: optional_u32(message, "multiplier_milli", defaults.multiplier_milli)?,
    })
}

fn validate_policy(
    canonical: &str,
    policy: &RpcPolicy,
    streaming: bool,
    server_streaming: bool,
) -> io::Result<()> {
    if !(1..=600_000).contains(&policy.timeout_ms) {
        return policy_error(canonical, "timeout_ms must be between 1 and 600000");
    }
    if server_streaming {
        if !(1..=600_000).contains(&policy.idle_timeout_ms) {
            return policy_error(
                canonical,
                "idle_timeout_ms must be between 1 and 600000 for streams",
            );
        }
    } else if policy.idle_timeout_ms != 0 {
        return policy_error(
            canonical,
            "idle_timeout_ms is valid only for streaming RPCs",
        );
    }
    if !(1..=8).contains(&policy.retry.max_attempts) {
        return policy_error(canonical, "retry max_attempts must be between 1 and 8");
    }
    if !(1..=60_000).contains(&policy.retry.initial_backoff_ms)
        || policy.retry.max_backoff_ms < policy.retry.initial_backoff_ms
        || policy.retry.max_backoff_ms > 300_000
        || !(1_000..=10_000).contains(&policy.retry.multiplier_milli)
    {
        return policy_error(canonical, "retry backoff settings are outside safe ranges");
    }
    if policy.retry.max_attempts > 1 && policy.idempotency == Idempotency::Unspecified {
        return policy_error(
            canonical,
            "retries require read-only, idempotent, or idempotency-key semantics",
        );
    }
    if streaming && policy.retry.max_attempts > 1 {
        return policy_error(canonical, "streaming RPCs cannot enable transparent retry");
    }
    for (name, value) in [
        ("max_request_bytes", policy.max_request_bytes),
        ("max_response_bytes", policy.max_response_bytes),
    ] {
        if !(1..=64 * 1024 * 1024).contains(&value) {
            return policy_error(canonical, format!("{name} must be between 1 and 67108864"));
        }
    }
    if policy.required_scopes.len() > 32 {
        return policy_error(canonical, "at most 32 required scopes are permitted");
    }
    for scope in &policy.required_scopes {
        if scope.is_empty()
            || scope.len() > 128
            || !scope.bytes().all(|byte| {
                byte.is_ascii_lowercase()
                    || byte.is_ascii_digit()
                    || matches!(byte, b'.' | b'_' | b':' | b'-')
            })
        {
            return policy_error(canonical, format!("invalid required scope {scope:?}"));
        }
    }
    if policy.trace_sample_per_million > 1_000_000 {
        return policy_error(
            canonical,
            "trace_sample_per_million must not exceed 1000000",
        );
    }
    Ok(())
}

fn optional_u32(message: &DynamicMessage, name: &str, default: u32) -> io::Result<u32> {
    if !message.has_field_by_name(name) {
        return Ok(default);
    }
    match message.get_field_by_name(name).as_deref() {
        Some(Value::U32(value)) => Ok(*value),
        _ => Err(invalid(format!("field {name} is not uint32"))),
    }
}

fn enum_number(message: &DynamicMessage, name: &str) -> io::Result<i32> {
    match message.get_field_by_name(name).as_deref() {
        Some(Value::EnumNumber(value)) => Ok(*value),
        _ => Err(invalid(format!("field {name} is not an enum"))),
    }
}

fn message_field<'a>(
    message: &'a DynamicMessage,
    name: &str,
) -> io::Result<Option<&'a DynamicMessage>> {
    if !message.has_field_by_name(name) {
        return Ok(None);
    }
    message
        .fields()
        .find_map(|(field, value)| {
            (field.name() == name)
                .then_some(value)
                .and_then(|value| match value {
                    Value::Message(value) => Some(value),
                    _ => None,
                })
        })
        .ok_or_else(|| invalid(format!("field {name} is not a message")))
        .map(Some)
}

fn string_list(message: &DynamicMessage, name: &str) -> io::Result<Vec<String>> {
    match message.get_field_by_name(name).as_deref() {
        Some(Value::List(values)) => values
            .iter()
            .map(|value| match value {
                Value::String(value) => Ok(value.clone()),
                _ => Err(invalid(format!("field {name} contains a non-string value"))),
            })
            .collect(),
        _ => Err(invalid(format!("field {name} is not repeated string"))),
    }
}

fn policy_error<T>(canonical: &str, message: impl std::fmt::Display) -> io::Result<T> {
    Err(invalid(format!("RPC {canonical}: {message}")))
}

fn invalid(error: impl std::fmt::Display) -> io::Error {
    io::Error::new(io::ErrorKind::InvalidData, error.to_string())
}
